/* $Id: IncrementalIngester.java 988245 2010-08-23 18:39:35Z kwright $ */

/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.manifoldcf.agents.incrementalingest;

import org.apache.manifoldcf.core.interfaces.*;
import org.apache.manifoldcf.agents.interfaces.*;
import org.apache.manifoldcf.agents.system.Logging;
import org.apache.manifoldcf.agents.system.ManifoldCF;
import java.util.*;
import java.io.*;

/** Incremental ingestion API implementation.
* This class is responsible for keeping track of what has been sent where, and also the corresponding version of
* each document so indexed.  The space over which this takes place is defined by the individual output connection - that is, the output connection
* seems to "remember" what documents were handed to it.
*
* A secondary purpose of this module is to provide a mapping between the key by which a document is described internally (by an
* identifier hash, plus the name of an identifier space), and the way the document is identified in the output space (by the name of an
* output connection, plus a URI which is considered local to that output connection space).
*
* <br><br>
* <b>ingeststatus</b>
* <table border="1" cellpadding="3" cellspacing="0">
* <tr class="TableHeadingColor">
* <th>Field</th><th>Type</th><th>Description&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;</th>
* <tr><td>id</td><td>BIGINT</td><td>Primary Key</td></tr>
* <tr><td>connectionname</td><td>VARCHAR(32)</td><td>Reference:outputconnections.connectionname</td></tr>
* <tr><td>dockey</td><td>VARCHAR(73)</td><td></td></tr>
* <tr><td>docuri</td><td>LONGTEXT</td><td></td></tr>
* <tr><td>urihash</td><td>VARCHAR(40)</td><td></td></tr>
* <tr><td>lastversion</td><td>LONGTEXT</td><td></td></tr>
* <tr><td>lastoutputversion</td><td>LONGTEXT</td><td></td></tr>
* <tr><td>lasttransformationversion</td><td>LONGTEXT</td><td></td></tr>
* <tr><td>forcedparams</td><td>LONGTEXT</td><td></td></tr>
* <tr><td>changecount</td><td>BIGINT</td><td></td></tr>
* <tr><td>firstingest</td><td>BIGINT</td><td></td></tr>
* <tr><td>lastingest</td><td>BIGINT</td><td></td></tr>
* <tr><td>authorityname</td><td>VARCHAR(32)</td><td></td></tr>
* </table>
* <br><br>
* 
*/
public class IncrementalIngester extends org.apache.manifoldcf.core.database.BaseTable implements IIncrementalIngester
{
  public static final String _rcsid = "@(#)$Id: IncrementalIngester.java 988245 2010-08-23 18:39:35Z kwright $";

  // Fields
  protected final static String idField = "id";
  protected final static String outputConnNameField = "connectionname";
  protected final static String docKeyField = "dockey";
  protected final static String docURIField = "docuri";
  protected final static String uriHashField = "urihash";
  protected final static String lastVersionField = "lastversion";
  protected final static String lastOutputVersionField = "lastoutputversion";
  protected final static String lastTransformationVersionField = "lasttransformationversion";
  protected final static String forcedParamsField = "forcedparams";
  protected final static String changeCountField = "changecount";
  protected final static String firstIngestField = "firstingest";
  protected final static String lastIngestField = "lastingest";
  protected final static String authorityNameField = "authorityname";

  // Thread context.
  protected final IThreadContext threadContext;
  // Lock manager.
  protected final ILockManager lockManager;
  // Output connection manager
  protected final IOutputConnectionManager connectionManager;
  // Output connector pool manager
  protected final IOutputConnectorPool outputConnectorPool;
  // Transformation connection manager
  protected final ITransformationConnectionManager transformationConnectionManager;
  // Transformation connector pool manager
  protected final ITransformationConnectorPool transformationConnectorPool;
  
  /** Constructor.
  */
  public IncrementalIngester(IThreadContext threadContext, IDBInterface database)
    throws ManifoldCFException
  {
    super(database,"ingeststatus");
    this.threadContext = threadContext;
    lockManager = LockManagerFactory.make(threadContext);
    connectionManager = OutputConnectionManagerFactory.make(threadContext);
    outputConnectorPool = OutputConnectorPoolFactory.make(threadContext);
    transformationConnectionManager = TransformationConnectionManagerFactory.make(threadContext);
    transformationConnectorPool = TransformationConnectorPoolFactory.make(threadContext);
  }

  /** Install the incremental ingestion manager.
  */
  @Override
  public void install()
    throws ManifoldCFException
  {
    String outputConnectionTableName = connectionManager.getTableName();
    String outputConnectionNameField = connectionManager.getConnectionNameColumn();

    // We always include an outer loop, because some upgrade conditions require retries.
    while (true)
    {
      // Postgresql has a limitation on the number of characters that can be indexed in a column.  So we use hashes instead.
      Map existing = getTableSchema(null,null);
      if (existing == null)
      {
        HashMap map = new HashMap();
        map.put(idField,new ColumnDescription("BIGINT",true,false,null,null,false));
        map.put(outputConnNameField,new ColumnDescription("VARCHAR(32)",false,false,outputConnectionTableName,outputConnectionNameField,false));
        map.put(docKeyField,new ColumnDescription("VARCHAR(73)",false,false,null,null,false));
        // The document URI field, if null, indicates that the document was not actually ingested!
        // This happens when a connector wishes to keep track of a version string, but not actually ingest the doc.
        map.put(docURIField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
        map.put(uriHashField,new ColumnDescription("VARCHAR(40)",false,true,null,null,false));
        map.put(lastVersionField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
        map.put(lastOutputVersionField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
        map.put(lastTransformationVersionField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
        map.put(forcedParamsField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
        map.put(changeCountField,new ColumnDescription("BIGINT",false,false,null,null,false));
        map.put(firstIngestField,new ColumnDescription("BIGINT",false,false,null,null,false));
        map.put(lastIngestField,new ColumnDescription("BIGINT",false,false,null,null,false));
        map.put(authorityNameField,new ColumnDescription("VARCHAR(32)",false,true,null,null,false));
        performCreate(map,null);
      }
      else
      {
        // Schema upgrade from 1.1 to 1.2
        ColumnDescription cd = (ColumnDescription)existing.get(forcedParamsField);
        if (cd == null)
        {
          Map<String,ColumnDescription> addMap = new HashMap<String,ColumnDescription>();
          addMap.put(forcedParamsField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
          performAlter(addMap,null,null,null);
        }
        
        // Schema upgrade from 1.6 to 1.7
        cd = (ColumnDescription)existing.get(lastTransformationVersionField);
        if (cd == null)
        {
          Map<String,ColumnDescription> addMap = new HashMap<String,ColumnDescription>();
          addMap.put(lastTransformationVersionField,new ColumnDescription("LONGTEXT",false,true,null,null,false));
          performAlter(addMap,null,null,null);
        }

      }

      // Now, do indexes
      IndexDescription keyIndex = new IndexDescription(true,new String[]{docKeyField,outputConnNameField});
      IndexDescription uriHashIndex = new IndexDescription(false,new String[]{uriHashField,outputConnNameField});
      IndexDescription outputConnIndex = new IndexDescription(false,new String[]{outputConnNameField});

      // Get rid of indexes that shouldn't be there
      Map indexes = getTableIndexes(null,null);
      Iterator iter = indexes.keySet().iterator();
      while (iter.hasNext())
      {
        String indexName = (String)iter.next();
        IndexDescription id = (IndexDescription)indexes.get(indexName);

        if (keyIndex != null && id.equals(keyIndex))
          keyIndex = null;
        else if (uriHashIndex != null && id.equals(uriHashIndex))
          uriHashIndex = null;
        else if (outputConnIndex != null && id.equals(outputConnIndex))
          outputConnIndex = null;
        else if (indexName.indexOf("_pkey") == -1)
          // This index shouldn't be here; drop it
          performRemoveIndex(indexName);
      }

      // Add the ones we didn't find
      if (uriHashIndex != null)
        performAddIndex(null,uriHashIndex);

      if (keyIndex != null)
        performAddIndex(null,keyIndex);

      if (outputConnIndex != null)
        performAddIndex(null,outputConnIndex);
      
      // All done; break out of loop
      break;
    }

  }

  /** Uninstall the incremental ingestion manager.
  */
  @Override
  public void deinstall()
    throws ManifoldCFException
  {
    performDrop(null);
  }

  /** Flush all knowledge of what was ingested before.
  */
  @Override
  public void clearAll()
    throws ManifoldCFException
  {
    performDelete("",null,null);
  }

  /** From a pipeline specification, get the name of the output connection that will be indexed last
  * in the pipeline.
  *@param pipelineSpecificationBasic is the basic pipeline specification.
  *@return the last indexed output connection name.
  */
  @Override
  public String getLastIndexedOutputConnectionName(IPipelineSpecificationBasic pipelineSpecificationBasic)
  {
    // It's always the last in the sequence.
    int count = pipelineSpecificationBasic.getOutputCount();
    if (count == 0)
      return null;
    return pipelineSpecificationBasic.getStageConnectionName(count-1);
  }

  /** Check if a mime type is indexable.
  *@param pipelineSpecification is the pipeline specification.
  *@param mimeType is the mime type to check.
  *@param activity are the activities available to this method.
  *@return true if the mimeType is indexable.
  */
  @Override
  public boolean checkMimeTypeIndexable(
    IPipelineSpecification pipelineSpecification,
    String mimeType,
    IOutputCheckActivity activity)
    throws ManifoldCFException, ServiceInterruption
  {
    PipelineObject pipeline = pipelineGrab(
      new PipelineConnections(pipelineSpecification));
    if (pipeline == null)
      // A connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("One or more connectors are not installed",0L);
    try
    {
      return pipeline.checkMimeTypeIndexable(mimeType,activity);
    }
    finally
    {
      pipeline.release();
    }
  }

  /** Check if a file is indexable.
  *@param pipelineSpecification is the pipeline specification.
  *@param localFile is the local file to check.
  *@param activity are the activities available to this method.
  *@return true if the local file is indexable.
  */
  @Override
  public boolean checkDocumentIndexable(
    IPipelineSpecification pipelineSpecification,
    File localFile,
    IOutputCheckActivity activity)
    throws ManifoldCFException, ServiceInterruption
  {
    PipelineObject pipeline = pipelineGrab(
      new PipelineConnections(pipelineSpecification));
    if (pipeline == null)
      // A connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("One or more connectors are not installed",0L);
    try
    {
      return pipeline.checkDocumentIndexable(localFile,activity);
    }
    finally
    {
      pipeline.release();
    }
  }

  /** Pre-determine whether a document's length is indexable by this connector.  This method is used by participating repository connectors
  * to help filter out documents that are too long to be indexable.
  *@param pipelineSpecification is the pipeline specification.
  *@param length is the length of the document.
  *@param activity are the activities available to this method.
  *@return true if the file is indexable.
  */
  @Override
  public boolean checkLengthIndexable(
    IPipelineSpecification pipelineSpecification,
    long length,
    IOutputCheckActivity activity)
    throws ManifoldCFException, ServiceInterruption
  {
    PipelineObject pipeline = pipelineGrab(
      new PipelineConnections(pipelineSpecification));
    if (pipeline == null)
      // A connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("One or more connectors are not installed",0L);
    try
    {
      return pipeline.checkLengthIndexable(length,activity);
    }
    finally
    {
      pipeline.release();
    }
  }

  /** Pre-determine whether a document's URL is indexable by this connector.  This method is used by participating repository connectors
  * to help filter out documents that not indexable.
  *@param pipelineSpecification is the pipeline specification.
  *@param url is the url of the document.
  *@param activity are the activities available to this method.
  *@return true if the file is indexable.
  */
  @Override
  public boolean checkURLIndexable(
    IPipelineSpecification pipelineSpecification,
    String url,
    IOutputCheckActivity activity)
    throws ManifoldCFException, ServiceInterruption
  {
    PipelineObject pipeline = pipelineGrab(
      new PipelineConnections(pipelineSpecification));
    if (pipeline == null)
      // A connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("One or more connectors are not installed",0L);
    try
    {
      return pipeline.checkURLIndexable(url,activity);
    }
    finally
    {
      pipeline.release();
    }
  }

  /** Grab the entire pipeline.
  *@param transformationConnections - the transformation connections, in order
  *@param outputConnection - the output connection
  *@param transformationDescriptionStrings - the array of description strings for transformations
  *@param outputDescriptionString - the output description string
  *@return the pipeline description, or null if any part of the pipeline cannot be grabbed.
  */
  protected PipelineObjectWithVersions pipelineGrabWithVersions(PipelineConnectionsWithVersions pipelineConnections)
    throws ManifoldCFException
  {
    // Pick up all needed transformation connectors
    ITransformationConnector[] transformationConnectors = transformationConnectorPool.grabMultiple(pipelineConnections.getTransformationConnectionNames(),pipelineConnections.getTransformationConnections());
    for (ITransformationConnector c : transformationConnectors)
    {
      if (c == null)
      {
        transformationConnectorPool.releaseMultiple(pipelineConnections.getTransformationConnections(),transformationConnectors);
        return null;
      }
    }
    
    // Pick up all needed output connectors.  If this fails we have to release the transformation connectors.
    try
    {
      IOutputConnector[] outputConnectors = outputConnectorPool.grabMultiple(pipelineConnections.getOutputConnectionNames(),pipelineConnections.getOutputConnections());
      for (IOutputConnector c : outputConnectors)
      {
        if (c == null)
        {
          outputConnectorPool.releaseMultiple(pipelineConnections.getOutputConnections(),outputConnectors);
          transformationConnectorPool.releaseMultiple(pipelineConnections.getTransformationConnections(),transformationConnectors);
          return null;
        }
      }
      return new PipelineObjectWithVersions(pipelineConnections,transformationConnectors,outputConnectors);
    }
    catch (Throwable e)
    {
      transformationConnectorPool.releaseMultiple(pipelineConnections.getTransformationConnections(),transformationConnectors);
      if (e instanceof ManifoldCFException)
        throw (ManifoldCFException)e;
      else if (e instanceof RuntimeException)
        throw (RuntimeException)e;
      else if (e instanceof Error)
        throw (Error)e;
      else
        throw new RuntimeException("Unexpected exception type: "+e.getClass().getName()+": "+e.getMessage(),e);
    }
  }

  /** Grab the entire pipeline.
  *@param transformationConnections - the transformation connections, in order
  *@param outputConnection - the output connection
  *@param transformationDescriptionStrings - the array of description strings for transformations
  *@param outputDescriptionString - the output description string
  *@return the pipeline description, or null if any part of the pipeline cannot be grabbed.
  */
  protected PipelineObject pipelineGrab(PipelineConnections pipelineConnections)
    throws ManifoldCFException
  {
    // Pick up all needed transformation connectors
    ITransformationConnector[] transformationConnectors = transformationConnectorPool.grabMultiple(pipelineConnections.getTransformationConnectionNames(),pipelineConnections.getTransformationConnections());
    for (ITransformationConnector c : transformationConnectors)
    {
      if (c == null)
      {
        transformationConnectorPool.releaseMultiple(pipelineConnections.getTransformationConnections(),transformationConnectors);
        return null;
      }
    }
    
    // Pick up all needed output connectors.  If this fails we have to release the transformation connectors.
    try
    {
      IOutputConnector[] outputConnectors = outputConnectorPool.grabMultiple(pipelineConnections.getOutputConnectionNames(),pipelineConnections.getOutputConnections());
      for (IOutputConnector c : outputConnectors)
      {
        if (c == null)
        {
          outputConnectorPool.releaseMultiple(pipelineConnections.getOutputConnections(),outputConnectors);
          transformationConnectorPool.releaseMultiple(pipelineConnections.getTransformationConnections(),transformationConnectors);
          return null;
        }
      }
      return new PipelineObject(pipelineConnections,transformationConnectors,outputConnectors);
    }
    catch (Throwable e)
    {
      transformationConnectorPool.releaseMultiple(pipelineConnections.getTransformationConnections(),transformationConnectors);
      if (e instanceof ManifoldCFException)
        throw (ManifoldCFException)e;
      else if (e instanceof RuntimeException)
        throw (RuntimeException)e;
      else if (e instanceof Error)
        throw (Error)e;
      else
        throw new RuntimeException("Unexpected exception type: "+e.getClass().getName()+": "+e.getMessage(),e);
    }
  }

  /** Get an output version string for a document.
  *@param outputConnectionName is the name of the output connection associated with this action.
  *@param spec is the output specification.
  *@return the description string.
  */
  @Override
  public VersionContext getOutputDescription(String outputConnectionName, Specification spec)
    throws ManifoldCFException, ServiceInterruption
  {
    IOutputConnection connection = connectionManager.load(outputConnectionName);
    IOutputConnector connector = outputConnectorPool.grab(connection);
    if (connector == null)
      // The connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("Output connector not installed",0L);
    try
    {
      return connector.getPipelineDescription(spec);
    }
    finally
    {
      outputConnectorPool.release(connection,connector);
    }

  }

  /** Get transformation version string for a document.
  *@param transformationConnectionName is the names of the transformation connection associated with this action.
  *@param spec is the transformation specification.
  *@return the description string.
  */
  @Override
  public VersionContext getTransformationDescription(String transformationConnectionName, Specification spec)
    throws ManifoldCFException, ServiceInterruption
  {
    ITransformationConnection connection = transformationConnectionManager.load(transformationConnectionName);
    ITransformationConnector connector = transformationConnectorPool.grab(connection);
    if (connector == null)
      // The connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("Transformation connector not installed",0L);
    try
    {
      return connector.getPipelineDescription(spec);
    }
    finally
    {
      transformationConnectorPool.release(connection,connector);
    }
  }

  /** Determine whether we need to fetch or refetch a document.
  * Pass in information including the pipeline specification with existing version info, plus new document and parameter version strings.
  * If no outputs need to be updated, then this method will return false.  If any outputs need updating, then true is returned.
  *@param pipelineSpecificationWithVersions is the pipeline specification including new version info for all transformation and output
  *  connections.
  *@param newDocumentVersion is the newly-determined document version.
  *@param newParameterVersion is the newly-determined parameter version.
  *@param newAuthorityNameString is the newly-determined authority name.
  *@return true if the document needs to be refetched.
  */
  @Override
  public boolean checkFetchDocument(
    IPipelineSpecificationWithVersions pipelineSpecificationWithVersions,
    String newDocumentVersion,
    String newParameterVersion,
    String newAuthorityNameString)
  {
    IPipelineSpecification pipelineSpecification = pipelineSpecificationWithVersions.getPipelineSpecification();
    IPipelineSpecificationBasic basicSpecification = pipelineSpecification.getBasicPipelineSpecification();
    // Empty document version has a special meaning....
    if (newDocumentVersion.length() == 0)
      return true;
    // Otherwise, cycle through the outputs
    for (int i = 0; i < basicSpecification.getOutputCount(); i++)
    {
      int stage = basicSpecification.getOutputStage(i);
      String oldDocumentVersion = pipelineSpecificationWithVersions.getOutputDocumentVersionString(i);
      String oldParameterVersion = pipelineSpecificationWithVersions.getOutputParameterVersionString(i);
      String oldOutputVersion = pipelineSpecificationWithVersions.getOutputVersionString(i);
      String oldAuthorityName = pipelineSpecificationWithVersions.getAuthorityNameString(i);
      // If it looks like we never indexed this output before, we need to do it now.
      if (oldDocumentVersion == null)
        return true;
      // Look first at the version strings that aren't pipeline dependent
      if (!oldDocumentVersion.equals(newDocumentVersion) ||
        !oldParameterVersion.equals(newParameterVersion) ||
        !oldAuthorityName.equals(newAuthorityNameString) ||
        !oldOutputVersion.equals(pipelineSpecification.getStageDescriptionString(stage)))
        return true;
      
      // Everything matches so far.  Next step is to compute a transformation path an corresponding version string.
      String newTransformationVersion = computePackedTransformationVersion(pipelineSpecification,stage);
      if (!pipelineSpecificationWithVersions.getOutputTransformationVersionString(i).equals(newTransformationVersion))
        return true;
    }
    // Everything matches, so no reindexing is needed.
    return false;
  }

  /** Compute a transformation version given a pipeline specification and starting output stage.
  *@param pipelineSpecification is the pipeline specification.
  *@param stage is the stage number of the output stage.
  *@return the transformation version string, which will be a composite of all the transformations applied.
  */
  protected static String computePackedTransformationVersion(IPipelineSpecification pipelineSpecification, int stage)
  {
    IPipelineSpecificationBasic basicSpecification = pipelineSpecification.getBasicPipelineSpecification();
    // First, count the stages we need to represent
    int stageCount = 0;
    int currentStage = stage;
    while (true)
    {
      int newStage = basicSpecification.getStageParent(currentStage);
      if (newStage == -1)
        break;
      stageCount++;
      currentStage = newStage;
    }
    // Doesn't matter how we pack it; I've chosen to do it in reverse for convenience
    String[] stageNames = new String[stageCount];
    String[] stageDescriptions = new String[stageCount];
    stageCount = 0;
    currentStage = stage;
    while (true)
    {
      int newStage = basicSpecification.getStageParent(currentStage);
      if (newStage == -1)
        break;
      stageNames[stageCount] = basicSpecification.getStageConnectionName(newStage);
      stageDescriptions[stageCount] = pipelineSpecification.getStageDescriptionString(newStage).getVersionString();
      stageCount++;
      currentStage = newStage;
    }
    // Finally, do the packing.
    StringBuilder sb = new StringBuilder();
    packList(sb,stageNames,'+');
    packList(sb,stageDescriptions,'!');
    return sb.toString();
  }
  
  protected static void packList(StringBuilder output, String[] values, char delimiter)
  {
    pack(output,Integer.toString(values.length),delimiter);
    int i = 0;
    while (i < values.length)
    {
      pack(output,values[i++],delimiter);
    }
  }

  protected static void pack(StringBuilder sb, String value, char delim)
  {
    for (int i = 0; i < value.length(); i++)
    {
      char x = value.charAt(i);
      if (x == delim || x == '\\')
      {
        sb.append('\\');
      }
      sb.append(x);
    }
    sb.append(delim);
  }

  /** Record a document version, but don't ingest it.
  * The purpose of this method is to keep track of the frequency at which ingestion "attempts" take place.
  * ServiceInterruption is thrown if this action must be rescheduled.
  *@param pipelineSpecificationBasic is the basic pipeline specification needed.
  *@param identifierClass is the name of the space in which the identifier hash should be interpreted.
  *@param identifierHash is the hashed document identifier.
  *@param documentVersion is the document version.
  *@param recordTime is the time at which the recording took place, in milliseconds since epoch.
  *@param activities is the object used in case a document needs to be removed from the output index as the result of this operation.
  */
  @Override
  public void documentRecord(
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String identifierClass, String identifierHash,
    String documentVersion, long recordTime,
    IOutputActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    String docKey = makeKey(identifierClass,identifierHash);

    String[] outputConnectionNames = extractOutputConnectionNames(pipelineSpecificationBasic);
    IOutputConnection[] outputConnections = connectionManager.loadMultiple(outputConnectionNames);

    if (Logging.ingest.isDebugEnabled())
    {
      Logging.ingest.debug("Recording document '"+docKey+"' for output connections '"+outputConnectionNames+"'");
    }

    for (int k = 0; k < outputConnectionNames.length; k++)
    {
      String outputConnectionName = outputConnectionNames[k];
      IOutputConnection connection = outputConnections[k];

      String oldURI = null;
      String oldURIHash = null;
      String oldOutputVersion = null;

      // Repeat if needed
      while (true)
      {
        long sleepAmt = 0L;
        try
        {
          // See what uri was used before for this doc, if any
          ArrayList list = new ArrayList();
          String query = buildConjunctionClause(list,new ClauseDescription[]{
            new UnitaryClause(docKeyField,docKey),
            new UnitaryClause(outputConnNameField,outputConnectionName)});
            
          IResultSet set = performQuery("SELECT "+docURIField+","+uriHashField+","+lastOutputVersionField+" FROM "+getTableName()+
            " WHERE "+query,list,null,null);

          if (set.getRowCount() > 0)
          {
            IResultRow row = set.getRow(0);
            oldURI = (String)row.getValue(docURIField);
            oldURIHash = (String)row.getValue(uriHashField);
            oldOutputVersion = (String)row.getValue(lastOutputVersionField);
          }
          
          break;
        }
        catch (ManifoldCFException e)
        {
          // Look for deadlock and retry if so
          if (e.getErrorCode() == e.DATABASE_TRANSACTION_ABORT)
          {
            if (Logging.perf.isDebugEnabled())
              Logging.perf.debug("Aborted select looking for status: "+e.getMessage());
            sleepAmt = getSleepAmt();
            continue;
          }
          throw e;
        }
        finally
        {
          sleepFor(sleepAmt);
        }
      }

      // If uri hashes collide, then we must be sure to eliminate only the *correct* records from the table, or we will leave
      // dangling documents around.  So, all uri searches and comparisons MUST compare the actual uri as well.

      // But, since we need to insure that any given URI is only worked on by one thread at a time, use critical sections
      // to block the rare case that multiple threads try to work on the same URI.
      
      String[] lockArray = computeLockArray(null,oldURI,outputConnectionName);
      lockManager.enterLocks(null,null,lockArray);
      try
      {

        ArrayList list = new ArrayList();
        
        if (oldURI != null)
        {
          IOutputConnector connector = outputConnectorPool.grab(connection);
          if (connector == null)
            // The connector is not installed; treat this as a service interruption.
            throw new ServiceInterruption("Output connector not installed",0L);
          try
          {
            connector.removeDocument(oldURI,oldOutputVersion,new OutputRemoveActivitiesWrapper(activities,outputConnectionName));
          }
          finally
          {
            outputConnectorPool.release(connection,connector);
          }
          // Delete all records from the database that match the old URI, except for THIS record.
          list.clear();
          String query = buildConjunctionClause(list,new ClauseDescription[]{
            new UnitaryClause(uriHashField,"=",oldURIHash),
            new UnitaryClause(outputConnNameField,outputConnectionName)});
          list.add(docKey);
          performDelete("WHERE "+query+" AND "+docKeyField+"!=?",list,null);
        }

        // If we get here, it means we are noting that the document was examined, but that no change was required.  This is signaled
        // to noteDocumentIngest by having the null documentURI.
        noteDocumentIngest(outputConnectionName,docKey,documentVersion,null,null,null,null,recordTime,null,null);
      }
      finally
      {
        lockManager.leaveLocks(null,null,lockArray);
      }
    }
  }

  /** Ingest a document.
  * This ingests the document, and notes it.  If this is a repeat ingestion of the document, this
  * method also REMOVES ALL OLD METADATA.  When complete, the index will contain only the metadata
  * described by the RepositoryDocument object passed to this method.
  * ServiceInterruption is thrown if the document ingestion must be rescheduled.
  *@param pipelineSpecificationWithVersions is the pipeline specification with already-fetched output versioning information.
  *@param identifierClass is the name of the space in which the identifier hash should be interpreted.
  *@param identifierHash is the hashed document identifier.
  *@param documentVersion is the document version.
  *@param parameterVersion is the version string for the forced parameters.
  *@param authorityName is the name of the authority associated with the document, if any.
  *@param data is the document data.  The data is closed after ingestion is complete.
  *@param ingestTime is the time at which the ingestion took place, in milliseconds since epoch.
  *@param documentURI is the URI of the document, which will be used as the key of the document in the index.
  *@param activities is an object providing a set of methods that the implementer can use to perform the operation.
  *@return true if the ingest was ok, false if the ingest is illegal (and should not be repeated).
  *@throws IOException only if data stream throws an IOException.
  */
  @Override
  public boolean documentIngest(
    IPipelineSpecificationWithVersions pipelineSpecificationWithVersions,
    String identifierClass, String identifierHash,
    String documentVersion,
    String parameterVersion,
    String authorityName,
    RepositoryDocument document,
    long ingestTime, String documentURI,
    IOutputActivity activities)
    throws ManifoldCFException, ServiceInterruption, IOException
  {
    PipelineConnectionsWithVersions pipelineConnectionsWithVersions = new PipelineConnectionsWithVersions(pipelineSpecificationWithVersions);
    
    String docKey = makeKey(identifierClass,identifierHash);

    if (Logging.ingest.isDebugEnabled())
    {
      Logging.ingest.debug("Ingesting document '"+docKey+"' into output connections '"+extractOutputConnectionNames(pipelineSpecificationWithVersions.getPipelineSpecification().getBasicPipelineSpecification())+"'");
    }

    // Set indexing date
    document.setIndexingDate(new Date());
    
    // Set up a pipeline
    PipelineObjectWithVersions pipeline = pipelineGrabWithVersions(pipelineConnectionsWithVersions);
    if (pipeline == null)
      // A connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("Pipeline connector not installed",0L);
    try
    {
      return pipeline.addOrReplaceDocumentWithException(docKey,documentURI,document,documentVersion,parameterVersion,authorityName,activities,ingestTime) == IPipelineConnector.DOCUMENTSTATUS_ACCEPTED;
    }
    finally
    {
      pipeline.release();
    }
  }

  protected static String[] extractOutputConnectionNames(IPipelineSpecificationBasic pipelineSpecificationBasic)
  {
    String[] rval = new String[pipelineSpecificationBasic.getOutputCount()];
    for (int i = 0; i < rval.length; i++)
    {
      rval[i] = pipelineSpecificationBasic.getStageConnectionName(pipelineSpecificationBasic.getOutputStage(i));
    }
    return rval;
  }
  
  /** Note the fact that we checked a document (and found that it did not need to be ingested, because the
  * versions agreed).
  *@param pipelineSpecificationBasic is a pipeline specification.
  *@param identifierClasses are the names of the spaces in which the identifier hashes should be interpreted.
  *@param identifierHashes are the set of document identifier hashes.
  *@param checkTime is the time at which the check took place, in milliseconds since epoch.
  */
  @Override
  public void documentCheckMultiple(
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String[] identifierClasses, String[] identifierHashes,
    long checkTime)
    throws ManifoldCFException
  {
    // Extract output connection names from pipeline spec
    String[] outputConnectionNames = extractOutputConnectionNames(pipelineSpecificationBasic);
    beginTransaction();
    try
    {
      int maxClauses;
      
      Set<String> docIDValues = new HashSet<String>();
      for (int j = 0; j < identifierHashes.length; j++)
      {
        String docDBString = makeKey(identifierClasses[j],identifierHashes[j]);
        docIDValues.add(docDBString);
      }

      // Now, perform n queries, each of them no larger the maxInClause in length.
      // Create a list of row id's from this.
      Set<Long> rowIDSet = new HashSet<Long>();
      Iterator<String> iter = docIDValues.iterator();
      int j = 0;
      List<String> list = new ArrayList<String>();
      maxClauses = maxClausesRowIdsForDocIds(outputConnectionNames);
      while (iter.hasNext())
      {
        if (j == maxClauses)
        {
          findRowIdsForDocIds(outputConnectionNames,rowIDSet,list);
          list.clear();
          j = 0;
        }
        list.add(iter.next());
        j++;
      }

      if (j > 0)
        findRowIdsForDocIds(outputConnectionNames,rowIDSet,list);

      // Now, break row id's into chunks too; submit one chunk at a time
      j = 0;
      List<Long> list2 = new ArrayList<Long>();
      Iterator<Long> iter2 = rowIDSet.iterator();
      maxClauses = maxClausesUpdateRowIds();
      while (iter2.hasNext())
      {
        if (j == maxClauses)
        {
          updateRowIds(list2,checkTime);
          list2.clear();
          j = 0;
        }
        list2.add(iter2.next());
        j++;
      }

      if (j > 0)
        updateRowIds(list2,checkTime);
    }
    catch (ManifoldCFException e)
    {
      signalRollback();
      throw e;
    }
    catch (Error e)
    {
      signalRollback();
      throw e;
    }
    finally
    {
      endTransaction();
    }
  }

  /** Note the fact that we checked a document (and found that it did not need to be ingested, because the
  * versions agreed).
  *@param pipelineSpecificationBasic is a basic pipeline specification.
  *@param identifierClass is the name of the space in which the identifier hash should be interpreted.
  *@param identifierHash is the hashed document identifier.
  *@param checkTime is the time at which the check took place, in milliseconds since epoch.
  */
  @Override
  public void documentCheck(
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String identifierClass, String identifierHash,
    long checkTime)
    throws ManifoldCFException
  {
    documentCheckMultiple(pipelineSpecificationBasic,new String[]{identifierClass},new String[]{identifierHash},checkTime);
  }

  /** Calculate the number of clauses.
  */
  protected int maxClausesUpdateRowIds()
  {
    return findConjunctionClauseMax(new ClauseDescription[]{});
  }
  
  /** Update a chunk of row ids.
  */
  protected void updateRowIds(List<Long> list, long checkTime)
    throws ManifoldCFException
  {
    ArrayList newList = new ArrayList();
    String query = buildConjunctionClause(newList,new ClauseDescription[]{
      new MultiClause(idField,list)});
      
    HashMap map = new HashMap();
    map.put(lastIngestField,new Long(checkTime));
    performUpdate(map,"WHERE "+query,newList,null);
  }


  /** Delete multiple documents from the search engine index.
  *@param pipelineSpecificationBasics are the pipeline specifications associated with the documents.
  *@param identifierClasses are the names of the spaces in which the identifier hashes should be interpreted.
  *@param identifierHashes is tha array of document identifier hashes if the documents.
  *@param activities is the object to use to log the details of the ingestion attempt.  May be null.
  */
  @Override
  public void documentDeleteMultiple(
    IPipelineSpecificationBasic[] pipelineSpecificationBasics,
    String[] identifierClasses, String[] identifierHashes,
    IOutputRemoveActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    // Segregate request by pipeline spec instance address.  Not perfect but works in the
    // environment it is used it.
    Map<IPipelineSpecificationBasic,List<Integer>> keyMap = new HashMap<IPipelineSpecificationBasic,List<Integer>>();
    for (int i = 0; i < pipelineSpecificationBasics.length; i++)
    {
      IPipelineSpecificationBasic spec = pipelineSpecificationBasics[i];
      List<Integer> list = keyMap.get(spec);
      if (list == null)
      {
        list = new ArrayList<Integer>();
        keyMap.put(spec,list);
      }
      list.add(new Integer(i));
    }

    // Create the return array.
    Iterator<IPipelineSpecificationBasic> iter = keyMap.keySet().iterator();
    while (iter.hasNext())
    {
      IPipelineSpecificationBasic spec = iter.next();
      List<Integer> list = keyMap.get(spec);
      String[] localIdentifierClasses = new String[list.size()];
      String[] localIdentifierHashes = new String[list.size()];
      for (int i = 0; i < localIdentifierClasses.length; i++)
      {
        int index = list.get(i).intValue();
        localIdentifierClasses[i] = identifierClasses[index];
        localIdentifierHashes[i] = identifierHashes[index];
      }
      documentDeleteMultiple(spec,localIdentifierClasses,localIdentifierHashes,activities);
    }
  }

  /** Delete multiple documents from the search engine index.
  *@param pipelineSpecificationBasic is the basic pipeline specification.
  *@param identifierClasses are the names of the spaces in which the identifier hashes should be interpreted.
  *@param identifierHashes is tha array of document identifier hashes if the documents.
  *@param activities is the object to use to log the details of the ingestion attempt.  May be null.
  */
  @Override
  public void documentDeleteMultiple(
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String[] identifierClasses, String[] identifierHashes,
    IOutputRemoveActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    String[] outputConnectionNames = extractOutputConnectionNames(pipelineSpecificationBasic);
    // Load connection managers up front to save time
    IOutputConnection[] outputConnections = connectionManager.loadMultiple(outputConnectionNames);
    
    // No transactions here, so we can cycle through the connection names one at a time
    for (int z = 0; z < outputConnectionNames.length; z++)
    {
      String outputConnectionName = outputConnectionNames[z];
      IOutputConnection connection = outputConnections[z];

      activities = new OutputRemoveActivitiesWrapper(activities,outputConnectionName);

      if (Logging.ingest.isDebugEnabled())
      {
        for (int i = 0; i < identifierHashes.length; i++)
        {
          Logging.ingest.debug("Request to delete document '"+makeKey(identifierClasses[i],identifierHashes[i])+"' from output connection '"+outputConnectionName+"'");
        }
      }

      // No transactions.  Time for the operation may exceed transaction timeout.

      // Obtain the current URIs of all of these.
      DeleteInfo[] uris = getDocumentURIMultiple(outputConnectionName,identifierClasses,identifierHashes);

      // Grab critical section locks so that we can't attempt to ingest at the same time we are deleting.
      // (This guarantees that when this operation is complete the database reflects reality.)
      int validURIcount = 0;
      for (int i = 0; i < uris.length; i++)
      {
        if (uris[i] != null && uris[i].getURI() != null)
          validURIcount++;
      }
      String[] lockArray = new String[validURIcount];
      String[] validURIArray = new String[validURIcount];
      validURIcount = 0;
      for (int i = 0; i < uris.length; i++)
      {
        if (uris[i] != null && uris[i].getURI() != null)
        {
          validURIArray[validURIcount] = uris[i].getURI();
          lockArray[validURIcount] = outputConnectionName+":"+validURIArray[validURIcount];
          validURIcount++;
        }
      }

      lockManager.enterLocks(null,null,lockArray);
      try
      {
        // Fetch the document URIs for the listed documents
        for (int i = 0; i < uris.length; i++)
        {
          if (uris[i] != null && uris[i].getURI() != null)
            removeDocument(connection,uris[i].getURI(),uris[i].getOutputVersion(),activities);
        }

        // Now, get rid of all rows that match the given uris.
        // Do the queries together, then the deletes
        beginTransaction();
        try
        {
          // The basic process is this:
          // 1) Come up with a set of urihash values
          // 2) Find the matching, corresponding id values
          // 3) Delete the rows corresponding to the id values, in sequence

          // Process (1 & 2) has to be broken down into chunks that contain the maximum
          // number of doc hash values each.  We need to avoid repeating doc hash values,
          // so the first step is to come up with ALL the doc hash values before looping
          // over them.

          int maxClauses;
          
          // Find all the documents that match this set of URIs
          Set<String> docURIHashValues = new HashSet<String>();
          Set<String> docURIValues = new HashSet<String>();
          for (String docDBString : validURIArray)
          {
            String docDBHashString = ManifoldCF.hash(docDBString);
            docURIValues.add(docDBString);
            docURIHashValues.add(docDBHashString);
          }

          // Now, perform n queries, each of them no larger the maxInClause in length.
          // Create a list of row id's from this.
          Set<Long> rowIDSet = new HashSet<Long>();
          Iterator<String> iter = docURIHashValues.iterator();
          int j = 0;
          List<String> hashList = new ArrayList<String>();
          maxClauses = maxClausesRowIdsForURIs(outputConnectionName);
          while (iter.hasNext())
          {
            if (j == maxClauses)
            {
              findRowIdsForURIs(outputConnectionName,rowIDSet,docURIValues,hashList);
              hashList.clear();
              j = 0;
            }
            hashList.add(iter.next());
            j++;
          }

          if (j > 0)
            findRowIdsForURIs(outputConnectionName,rowIDSet,docURIValues,hashList);

          // Next, go through the list of row IDs, and delete them in chunks
          j = 0;
          List<Long> list = new ArrayList<Long>();
          Iterator<Long> iter2 = rowIDSet.iterator();
          maxClauses = maxClausesDeleteRowIds();
          while (iter2.hasNext())
          {
            if (j == maxClauses)
            {
              deleteRowIds(list);
              list.clear();
              j = 0;
            }
            list.add(iter2.next());
            j++;
          }

          if (j > 0)
            deleteRowIds(list);

          // Now, find the set of documents that remain that match the document identifiers.
          Set<String> docIdValues = new HashSet<String>();
          for (int i = 0; i < identifierHashes.length; i++)
          {
            String docDBString = makeKey(identifierClasses[i],identifierHashes[i]);
            docIdValues.add(docDBString);
          }

          // Now, perform n queries, each of them no larger the maxInClause in length.
          // Create a list of row id's from this.
          rowIDSet.clear();
          iter = docIdValues.iterator();
          j = 0;
          List<String> list2 = new ArrayList<String>();
          maxClauses = maxClausesRowIdsForDocIds(outputConnectionName);
          while (iter.hasNext())
          {
            if (j == maxClauses)
            {
              findRowIdsForDocIds(outputConnectionName,rowIDSet,list2);
              list2.clear();
              j = 0;
            }
            list2.add(iter.next());
            j++;
          }

          if (j > 0)
            findRowIdsForDocIds(outputConnectionName,rowIDSet,list2);

          // Next, go through the list of row IDs, and delete them in chunks
          j = 0;
          list.clear();
          iter2 = rowIDSet.iterator();
          maxClauses = maxClausesDeleteRowIds();
          while (iter2.hasNext())
          {
            if (j == maxClauses)
            {
              deleteRowIds(list);
              list.clear();
              j = 0;
            }
            list.add(iter2.next());
            j++;
          }

          if (j > 0)
            deleteRowIds(list);

        }
        catch (ManifoldCFException e)
        {
          signalRollback();
          throw e;
        }
        catch (Error e)
        {
          signalRollback();
          throw e;
        }
        finally
        {
          endTransaction();
        }
      }
      finally
      {
        lockManager.leaveLocks(null,null,lockArray);
      }
    }
  }

  /** Calculate the clauses.
  */
  protected int maxClausesRowIdsForURIs(String outputConnectionName)
  {
    return findConjunctionClauseMax(new ClauseDescription[]{
      new UnitaryClause(outputConnNameField,outputConnectionName)});
  }
  
  /** Given values and parameters corresponding to a set of hash values, add corresponding
  * table row id's to the output map.
  */
  protected void findRowIdsForURIs(String outputConnectionName, Set<Long> rowIDSet, Set<String> uris, List<String> hashParamValues)
    throws ManifoldCFException
  {
    ArrayList list = new ArrayList();
    String query = buildConjunctionClause(list,new ClauseDescription[]{
      new MultiClause(uriHashField,hashParamValues),
      new UnitaryClause(outputConnNameField,outputConnectionName)});
      
    IResultSet set = performQuery("SELECT "+idField+","+docURIField+" FROM "+
      getTableName()+" WHERE "+query,list,null,null);
    
    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      String docURI = (String)row.getValue(docURIField);
      if (docURI != null && docURI.length() > 0)
      {
        if (uris.contains(docURI))
        {
          Long rowID = (Long)row.getValue(idField);
          rowIDSet.add(rowID);
        }
      }
    }
  }

  /** Calculate the maximum number of doc ids we should use.
  */
  protected int maxClausesRowIdsForDocIds(String outputConnectionName)
  {
    return findConjunctionClauseMax(new ClauseDescription[]{
      new UnitaryClause(outputConnNameField,outputConnectionName)});
  }

  /** Calculate the maximum number of doc ids we should use.
  */
  protected int maxClausesRowIdsForDocIds(String[] outputConnectionNames)
  {
    return findConjunctionClauseMax(new ClauseDescription[]{
      new MultiClause(outputConnNameField,outputConnectionNames)});
  }
  
  /** Given values and parameters corresponding to a set of hash values, add corresponding
  * table row id's to the output map.
  */
  protected void findRowIdsForDocIds(String outputConnectionName, Set<Long> rowIDSet, List<String> paramValues)
    throws ManifoldCFException
  {
    ArrayList list = new ArrayList();
    String query = buildConjunctionClause(list,new ClauseDescription[]{
      new MultiClause(docKeyField,paramValues),
      new UnitaryClause(outputConnNameField,outputConnectionName)});
      
    IResultSet set = performQuery("SELECT "+idField+" FROM "+
      getTableName()+" WHERE "+query,list,null,null);
    
    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      Long rowID = (Long)row.getValue(idField);
      rowIDSet.add(rowID);
    }
  }

  /** Given values and parameters corresponding to a set of hash values, add corresponding
  * table row id's to the output map.
  */
  protected void findRowIdsForDocIds(String[] outputConnectionNames, Set<Long> rowIDSet, List<String> paramValues)
    throws ManifoldCFException
  {
    ArrayList list = new ArrayList();
    String query = buildConjunctionClause(list,new ClauseDescription[]{
      new MultiClause(docKeyField,paramValues),
      new MultiClause(outputConnNameField,outputConnectionNames)});
      
    IResultSet set = performQuery("SELECT "+idField+" FROM "+
      getTableName()+" WHERE "+query,list,null,null);
    
    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      Long rowID = (Long)row.getValue(idField);
      rowIDSet.add(rowID);
    }
  }

  /** Calculate the maximum number of clauses.
  */
  protected int maxClausesDeleteRowIds()
  {
    return findConjunctionClauseMax(new ClauseDescription[]{});
  }
    
  /** Delete a chunk of row ids.
  */
  protected void deleteRowIds(List<Long> list)
    throws ManifoldCFException
  {
    ArrayList newList = new ArrayList();
    String query = buildConjunctionClause(newList,new ClauseDescription[]{
      new MultiClause(idField,list)});
    performDelete("WHERE "+query,newList,null);
  }

  /** Delete a document from the search engine index.
  *@param pipelineSpecificationBasic is the basic pipeline specification.
  *@param identifierClass is the name of the space in which the identifier hash should be interpreted.
  *@param identifierHash is the hash of the id of the document.
  *@param activities is the object to use to log the details of the ingestion attempt.  May be null.
  */
  @Override
  public void documentDelete(
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String identifierClass, String identifierHash,
    IOutputRemoveActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    documentDeleteMultiple(pipelineSpecificationBasic,new String[]{identifierClass},new String[]{identifierHash},activities);
  }

  /** Find out what URIs a SET of document URIs are currently ingested.
  *@param identifierHashes is the array of document id's to check.
  *@return the array of current document uri's.  Null returned for identifiers
  * that don't exist in the index.
  */
  protected DeleteInfo[] getDocumentURIMultiple(String outputConnectionName, String[] identifierClasses, String[] identifierHashes)
    throws ManifoldCFException
  {
    DeleteInfo[] rval = new DeleteInfo[identifierHashes.length];
    Map<String,Integer> map = new HashMap<String,Integer>();
    for (int i = 0; i < identifierHashes.length; i++)
    {
      map.put(makeKey(identifierClasses[i],identifierHashes[i]),new Integer(i));
      rval[i] = null;
    }

    beginTransaction();
    try
    {
      List<String> list = new ArrayList<String>();
      int maxCount = maxClauseDocumentURIChunk(outputConnectionName);
      int j = 0;
      Iterator<String> iter = map.keySet().iterator();
      while (iter.hasNext())
      {
        if (j == maxCount)
        {
          getDocumentURIChunk(rval,map,outputConnectionName,list);
          j = 0;
          list.clear();
        }
        list.add(iter.next());
        j++;
      }
      if (j > 0)
        getDocumentURIChunk(rval,map,outputConnectionName,list);
      return rval;
    }
    catch (ManifoldCFException e)
    {
      signalRollback();
      throw e;
    }
    catch (Error e)
    {
      signalRollback();
      throw e;
    }
    finally
    {
      endTransaction();
    }
  }

  /** Look up ingestion data for a set of documents.
  *@param rval is a map of output key to document data, in no particular order, which will be loaded with all matching results.
  *@param pipelineSpecificationBasics are the pipeline specifications corresponding to the identifier classes and hashes.
  *@param identifierClasses are the names of the spaces in which the identifier hashes should be interpreted.
  *@param identifierHashes is the array of document identifier hashes to look up.
  */
  @Override
  public void getPipelineDocumentIngestDataMultiple(
    Map<OutputKey,DocumentIngestStatus> rval,
    IPipelineSpecificationBasic[] pipelineSpecificationBasics,
    String[] identifierClasses, String[] identifierHashes)
    throws ManifoldCFException
  {
    // Organize by pipeline spec.
    Map<IPipelineSpecificationBasic,List<Integer>> keyMap = new HashMap<IPipelineSpecificationBasic,List<Integer>>();
    for (int i = 0; i < pipelineSpecificationBasics.length; i++)
    {
      IPipelineSpecificationBasic spec = pipelineSpecificationBasics[i];
      List<Integer> list = keyMap.get(spec);
      if (list == null)
      {
        list = new ArrayList<Integer>();
        keyMap.put(spec,list);
      }
      list.add(new Integer(i));
    }

    // Create the return array.
    Iterator<IPipelineSpecificationBasic> iter = keyMap.keySet().iterator();
    while (iter.hasNext())
    {
      IPipelineSpecificationBasic spec = iter.next();
      List<Integer> list = keyMap.get(spec);
      String[] localIdentifierClasses = new String[list.size()];
      String[] localIdentifierHashes = new String[list.size()];
      for (int i = 0; i < localIdentifierClasses.length; i++)
      {
        int index = list.get(i).intValue();
        localIdentifierClasses[i] = identifierClasses[index];
        localIdentifierHashes[i] = identifierHashes[index];
      }
      getPipelineDocumentIngestDataMultiple(rval,spec,localIdentifierClasses,localIdentifierHashes);
    }
  }

  /** Look up ingestion data for a SET of documents.
  *@param rval is a map of output key to document data, in no particular order, which will be loaded with all matching results.
  *@param pipelineSpecificationBasic is the pipeline specification for all documents.
  *@param identifierClasses are the names of the spaces in which the identifier hashes should be interpreted.
  *@param identifierHashes is the array of document identifier hashes to look up.
  */
  @Override
  public void getPipelineDocumentIngestDataMultiple(
    Map<OutputKey,DocumentIngestStatus> rval,
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String[] identifierClasses, String[] identifierHashes)
    throws ManifoldCFException
  {
    String[] outputConnectionNames = extractOutputConnectionNames(pipelineSpecificationBasic);

    // Build a map, so we can convert an identifier into an array index.
    Map<String,Integer> indexMap = new HashMap<String,Integer>();
    for (int i = 0; i < identifierHashes.length; i++)
    {
      indexMap.put(makeKey(identifierClasses[i],identifierHashes[i]),new Integer(i));
    }

    beginTransaction();
    try
    {
      List<String> list = new ArrayList<String>();
      int maxCount = maxClausePipelineDocumentIngestDataChunk(outputConnectionNames);
      int j = 0;
      Iterator<String> iter = indexMap.keySet().iterator();
      while (iter.hasNext())
      {
        if (j == maxCount)
        {
          getPipelineDocumentIngestDataChunk(rval,indexMap,outputConnectionNames,list,identifierClasses,identifierHashes);
          j = 0;
          list.clear();
        }
        list.add(iter.next());
        j++;
      }
      if (j > 0)
        getPipelineDocumentIngestDataChunk(rval,indexMap,outputConnectionNames,list,identifierClasses,identifierHashes);
    }
    catch (ManifoldCFException e)
    {
      signalRollback();
      throw e;
    }
    catch (Error e)
    {
      signalRollback();
      throw e;
    }
    finally
    {
      endTransaction();
    }

  }

  /** Get a chunk of document ingest data records.
  *@param rval is the document ingest status array where the data should be put.
  *@param map is the map from id to index.
  *@param clause is the in clause for the query.
  *@param list is the parameter list for the query.
  */
  protected void getPipelineDocumentIngestDataChunk(Map<OutputKey,DocumentIngestStatus> rval, Map<String,Integer> map, String[] outputConnectionNames, List<String> list,
    String[] identifierClasses, String[] identifierHashes)
    throws ManifoldCFException
  {
    ArrayList newList = new ArrayList();
    String query = buildConjunctionClause(newList,new ClauseDescription[]{
      new MultiClause(docKeyField,list),
      new MultiClause(outputConnNameField,outputConnectionNames)});
      
    // Get the primary records associated with this hash value
    IResultSet set = performQuery("SELECT "+idField+","+outputConnNameField+","+docKeyField+","+lastVersionField+","+lastOutputVersionField+","+authorityNameField+","+forcedParamsField+","+lastTransformationVersionField+
      " FROM "+getTableName()+" WHERE "+query,newList,null,null);

    // Now, go through the original request once more, this time building the result
    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      String docHash = row.getValue(docKeyField).toString();
      Integer position = map.get(docHash);
      if (position != null)
      {
        Long id = (Long)row.getValue(idField);
        String outputConnectionName = (String)row.getValue(outputConnNameField);
        String lastVersion = (String)row.getValue(lastVersionField);
        if (lastVersion == null)
          lastVersion = "";
        String lastTransformationVersion = (String)row.getValue(lastTransformationVersionField);
        if (lastTransformationVersion == null)
          lastTransformationVersion = "";
        String lastOutputVersion = (String)row.getValue(lastOutputVersionField);
        if (lastOutputVersion == null)
          lastOutputVersion = "";
        String paramVersion = (String)row.getValue(forcedParamsField);
        if (paramVersion == null)
          paramVersion = "";
        String authorityName = (String)row.getValue(authorityNameField);
        if (authorityName == null)
          authorityName = "";
        int indexValue = position.intValue();
        rval.put(new OutputKey(identifierClasses[indexValue],identifierHashes[indexValue],outputConnectionName),
          new DocumentIngestStatus(lastVersion,lastTransformationVersion,lastOutputVersion,paramVersion,authorityName));
      }
    }
  }
  
  /** Look up ingestion data for a document.
  *@param rval is a map of output key to document data, in no particular order, which will be loaded with all matching results.
  *@param pipelineSpecificationBasic is the pipeline specification for the document.
  *@param identifierClass is the name of the space in which the identifier hash should be interpreted.
  *@param identifierHash is the hash of the id of the document.
  */
  @Override
  public void getPipelineDocumentIngestData(
    Map<OutputKey,DocumentIngestStatus> rval,
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String identifierClass, String identifierHash)
    throws ManifoldCFException
  {
    getPipelineDocumentIngestDataMultiple(rval,pipelineSpecificationBasic,
      new String[]{identifierClass},new String[]{identifierHash});
  }

  /** Calculate the average time interval between changes for a document.
  * This is based on the data gathered for the document.
  *@param pipelineSpecificationBasic is the basic pipeline specification.
  *@param identifierClasses are the names of the spaces in which the identifier hashes should be interpreted.
  *@param identifierHashes is the hashes of the ids of the documents.
  *@return the number of milliseconds between changes, or 0 if this cannot be calculated.
  */
  @Override
  public long[] getDocumentUpdateIntervalMultiple(
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String[] identifierClasses, String[] identifierHashes)
    throws ManifoldCFException
  {
    // Get the output connection names
    String[] outputConnectionNames = extractOutputConnectionNames(pipelineSpecificationBasic);

    // Do these all at once!!
    // First, create a return array
    long[] rval = new long[identifierHashes.length];
    // Also create a map from identifier to return index.
    Map<String,Integer> returnMap = new HashMap<String,Integer>();
    // Finally, need the set of hash codes
    Set<String> idCodes = new HashSet<String>();
    for (int j = 0; j < identifierHashes.length; j++)
    {
      String key = makeKey(identifierClasses[j],identifierHashes[j]);
      rval[j] = Long.MAX_VALUE;
      returnMap.put(key,new Integer(j));
      idCodes.add(key);
    }

    // Get the chunk size
    int maxClause = maxClauseGetIntervals(outputConnectionNames);

    // Loop through the hash codes
    Iterator<String> iter = idCodes.iterator();
    List<String> list = new ArrayList<String>();
    int j = 0;
    while (iter.hasNext())
    {
      if (j == maxClause)
      {
        getIntervals(rval,outputConnectionNames,list,returnMap);
        list.clear();
        j = 0;
      }

      list.add(iter.next());
      j++;
    }

    if (j > 0)
      getIntervals(rval,outputConnectionNames,list,returnMap);

    for (int i = 0; i < rval.length; i++)
    {
      if (rval[i] == Long.MAX_VALUE)
        rval[i] = 0;
    }
    
    return rval;

  }

  /** Calculate the average time interval between changes for a document.
  * This is based on the data gathered for the document.
  *@param pipelineSpecificationBasic is the basic pipeline specification.
  *@param identifierClass is the name of the space in which the identifier hash should be interpreted.
  *@param identifierHash is the hash of the id of the document.
  *@return the number of milliseconds between changes, or 0 if this cannot be calculated.
  */
  @Override
  public long getDocumentUpdateInterval(
    IPipelineSpecificationBasic pipelineSpecificationBasic,
    String identifierClass, String identifierHash)
    throws ManifoldCFException
  {
    return getDocumentUpdateIntervalMultiple(
      pipelineSpecificationBasic,
      new String[]{identifierClass},new String[]{identifierHash})[0];
  }

  /** Calculate the number of clauses.
  */
  protected int maxClauseGetIntervals(String[] outputConnectionNames)
  {
    return findConjunctionClauseMax(new ClauseDescription[]{
      new MultiClause(outputConnNameField,outputConnectionNames)});
  }
  
  /** Query for and calculate the interval for a bunch of hashcodes.
  *@param rval is the array to stuff calculated return values into.
  *@param list is the list of parameters.
  *@param queryPart is the part of the query pertaining to the list of hashcodes
  *@param returnMap is a mapping from document id to rval index.
  */
  protected void getIntervals(long[] rval, String[] outputConnectionNames, List<String> list, Map<String,Integer> returnMap)
    throws ManifoldCFException
  {
    ArrayList newList = new ArrayList();
    String query = buildConjunctionClause(newList,new ClauseDescription[]{
      new MultiClause(docKeyField,list),
      new MultiClause(outputConnNameField,outputConnectionNames)});
      
    IResultSet set = performQuery("SELECT "+docKeyField+","+changeCountField+","+firstIngestField+","+lastIngestField+
      " FROM "+getTableName()+" WHERE "+query,newList,null,null);

    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      String docHash = (String)row.getValue(docKeyField);
      Integer index = (Integer)returnMap.get(docHash);
      if (index != null)
      {
        // Calculate the return value
        long changeCount = ((Long)row.getValue(changeCountField)).longValue();
        long firstIngest = ((Long)row.getValue(firstIngestField)).longValue();
        long lastIngest = ((Long)row.getValue(lastIngestField)).longValue();
        int indexValue = index.intValue();
        long newValue = (long)(((double)(lastIngest-firstIngest))/(double)changeCount);
        if (newValue < rval[indexValue])
          rval[indexValue] = newValue;
      }
    }
  }

  /** Reset all documents belonging to a specific output connection, because we've got information that
  * that system has been reconfigured.  This will force all such documents to be reindexed the next time
  * they are checked.
  *@param outputConnectionName is the name of the output connection associated with this action.
  */
  @Override
  public void resetOutputConnection(String outputConnectionName)
    throws ManifoldCFException
  {
    // We're not going to blow away the records, but we are going to set their versions to mean, "reindex required"
    HashMap map = new HashMap();
    map.put(lastVersionField,null);
    ArrayList list = new ArrayList();
    String query = buildConjunctionClause(list,new ClauseDescription[]{
      new UnitaryClause(outputConnNameField,outputConnectionName)});
      
    performUpdate(map,"WHERE "+query,list,null);
  }

  /** Remove all knowledge of an output index from the system.  This is appropriate
  * when the output index no longer exists and you wish to delete the associated job.
  *@param outputConnectionName is the name of the output connection associated with this action.
  */
  @Override
  public void removeOutputConnection(String outputConnectionName)
    throws ManifoldCFException
  {
    IOutputConnection connection = connectionManager.load(outputConnectionName);
    
    ArrayList list = new ArrayList();
    String query = buildConjunctionClause(list,new ClauseDescription[]{
      new UnitaryClause(outputConnNameField,outputConnectionName)});
      
    performDelete("WHERE "+query,list,null);
      
    // Notify the output connection of the removal of all the records for the connection
    IOutputConnector connector = outputConnectorPool.grab(connection);
    if (connector == null)
      return;
    try
    {
      connector.noteAllRecordsRemoved();
    }
    finally
    {
      outputConnectorPool.release(connection,connector);
    }

  }
  
  /** Note the ingestion of a document, or the "update" of a document.
  *@param outputConnectionName is the name of the output connection.
  *@param docKey is the key string describing the document.
  *@param documentVersion is a string describing the new version of the document.
  *@param transformationVersion is a string describing all current transformations for the document.
  *@param outputVersion is the version string calculated for the output connection.
  *@param authorityNameString is the name of the relevant authority connection.
  *@param packedForcedParameters is the string we use to determine differences in packed parameters.
  *@param ingestTime is the time at which the ingestion took place, in milliseconds since epoch.
  *@param documentURI is the uri the document can be accessed at, or null (which signals that we are to record the version, but no
  * ingestion took place).
  *@param documentURIHash is the hash of the document uri.
  */
  protected void noteDocumentIngest(String outputConnectionName,
    String docKey, String documentVersion, String transformationVersion,
    String outputVersion, String packedForcedParameters,
    String authorityNameString,
    long ingestTime, String documentURI, String documentURIHash)
    throws ManifoldCFException
  {
    HashMap map = new HashMap();
    while (true)
    {
      // The table can have at most one row per URI, for non-null URIs.  It can also have at most one row per document identifier.
      // However, for null URI's, multiple rows are allowed.  Null URIs have a special meaning, which is that
      // the document was not actually ingested.

      // To make sure the constraints are enforced, we cannot simply look for the row and insert one if not found.  This is because
      // postgresql does not cause a lock to be created on rows that don't yet exist, so multiple transactions of the kind described
      // can lead to multiple rows with the same key.  Instead, we *could* lock the whole table down, but that would interfere with
      // parallelism.  The lowest-impact approach is to make sure an index constraint is in place, and first attempt to do an INSERT.
      // That attempt will fail if a record already exists.  Then, an update can be attempted.
      //
      // In the situation where the INSERT fails, the current transaction is aborted and a new transaction must be performed.
      // This means that it is impossible to structure things so that the UPDATE is guaranteed to succeed.  So, on the event of an
      // INSERT failure, the UPDATE is tried, but if that fails too, then the INSERT is tried again.  This should also handle the
      // case where a DELETE in another transaction removes the database row before it can be UPDATEd.
      //
      // If the UPDATE does not appear to modify any rows, this is also a signal that the INSERT must be retried.
      //

      // Try the update first.  Typically this succeeds except in the case where a doc is indexed for the first time.
      map.clear();
      map.put(lastVersionField,documentVersion);
      map.put(lastTransformationVersionField,transformationVersion);
      map.put(lastOutputVersionField,outputVersion);
      map.put(forcedParamsField,packedForcedParameters);
      map.put(lastIngestField,new Long(ingestTime));
      if (documentURI != null)
      {
        map.put(docURIField,documentURI);
        map.put(uriHashField,documentURIHash);
      }
      if (authorityNameString != null)
        map.put(authorityNameField,authorityNameString);
      else
        map.put(authorityNameField,"");
      
      // Transaction abort due to deadlock should be retried here.
      while (true)
      {
        long sleepAmt = 0L;

        beginTransaction();
        try
        {
          // Look for existing row.
          ArrayList list = new ArrayList();
          String query = buildConjunctionClause(list,new ClauseDescription[]{
            new UnitaryClause(docKeyField,docKey),
            new UnitaryClause(outputConnNameField,outputConnectionName)});
          IResultSet set = performQuery("SELECT "+idField+","+changeCountField+" FROM "+getTableName()+" WHERE "+
            query+" FOR UPDATE",list,null,null);
          IResultRow row = null;
          if (set.getRowCount() > 0)
            row = set.getRow(0);

          if (row != null)
          {
            // Update the record
            list.clear();
            query = buildConjunctionClause(list,new ClauseDescription[]{
              new UnitaryClause(idField,row.getValue(idField))});
            long changeCount = ((Long)row.getValue(changeCountField)).longValue();
            changeCount++;
            map.put(changeCountField,new Long(changeCount));
            performUpdate(map,"WHERE "+query,list,null);
            // Update successful!
            performCommit();
            return;
          }

          // Update failed to find a matching record, so try the insert
          break;
        }
        catch (ManifoldCFException e)
        {
          signalRollback();
          if (e.getErrorCode() == e.DATABASE_TRANSACTION_ABORT)
          {
            if (Logging.perf.isDebugEnabled())
              Logging.perf.debug("Aborted transaction noting ingestion: "+e.getMessage());
            sleepAmt = getSleepAmt();
            continue;
          }

          throw e;
        }
        catch (Error e)
        {
          signalRollback();
          throw e;
        }
        finally
        {
          endTransaction();
          sleepFor(sleepAmt);
        }
      }

      // Set up for insert
      map.clear();
      map.put(lastVersionField,documentVersion);
      map.put(lastTransformationVersionField,transformationVersion);
      map.put(lastOutputVersionField,outputVersion);
      map.put(forcedParamsField,packedForcedParameters);
      map.put(lastIngestField,new Long(ingestTime));
      if (documentURI != null)
      {
        map.put(docURIField,documentURI);
        map.put(uriHashField,documentURIHash);
      }
      if (authorityNameString != null)
        map.put(authorityNameField,authorityNameString);
      else
        map.put(authorityNameField,"");

      Long id = new Long(IDFactory.make(threadContext));
      map.put(idField,id);
      map.put(outputConnNameField,outputConnectionName);
      map.put(docKeyField,docKey);
      map.put(changeCountField,new Long(1));
      map.put(firstIngestField,map.get(lastIngestField));
      beginTransaction();
      try
      {
        performInsert(map,null);
        noteModifications(1,0,0);
        performCommit();
        return;
      }
      catch (ManifoldCFException e)
      {
        signalRollback();
        // If this is simply a constraint violation, we just want to fall through and try the update!
        if (e.getErrorCode() != ManifoldCFException.DATABASE_TRANSACTION_ABORT)
          throw e;
        // Otherwise, exit transaction and fall through to 'update' attempt
      }
      catch (Error e)
      {
        signalRollback();
        throw e;
      }
      finally
      {
        endTransaction();
      }

      // Insert must have failed.  Attempt an update.
    }
  }

  /** Calculate how many clauses at a time
  */
  protected int maxClauseDocumentURIChunk(String outputConnectionName)
  {
    return findConjunctionClauseMax(new ClauseDescription[]{
      new UnitaryClause(outputConnNameField,outputConnectionName)});
  }
  
  /** Get a chunk of document uris.
  *@param rval is the string array where the uris should be put.
  *@param map is the map from id to index.
  *@param clause is the in clause for the query.
  *@param list is the parameter list for the query.
  */
  protected void getDocumentURIChunk(DeleteInfo[] rval, Map<String,Integer> map, String outputConnectionName, List<String> list)
    throws ManifoldCFException
  {
    ArrayList newList = new ArrayList();
    String query = buildConjunctionClause(newList,new ClauseDescription[]{
      new MultiClause(docKeyField,list),
      new UnitaryClause(outputConnNameField,outputConnectionName)});
      
    IResultSet set = performQuery("SELECT "+docKeyField+","+docURIField+","+lastOutputVersionField+" FROM "+getTableName()+" WHERE "+
      query,newList,null,null);

    // Go through list and put into buckets.
    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      String docHash = row.getValue(docKeyField).toString();
      Integer position = (Integer)map.get(docHash);
      if (position != null)
      {
        String lastURI = (String)row.getValue(docURIField);
        if (lastURI != null && lastURI.length() == 0)
          lastURI = null;
        String lastOutputVersion = (String)row.getValue(lastOutputVersionField);
        rval[position.intValue()] = new DeleteInfo(lastURI,lastOutputVersion);
      }
    }
  }

  /** Count the clauses
  */
  protected int maxClauseDocumentIngestDataChunk(String outputConnectionName)
  {
    return findConjunctionClauseMax(new ClauseDescription[]{
      new UnitaryClause(outputConnNameField,outputConnectionName)});
  }

  /** Count the clauses
  */
  protected int maxClausePipelineDocumentIngestDataChunk(String[] outputConnectionNames)
  {
    return findConjunctionClauseMax(new ClauseDescription[]{
      new MultiClause(outputConnNameField,outputConnectionNames)});
  }
  
  /** Get a chunk of document ingest data records.
  *@param rval is the document ingest status array where the data should be put.
  *@param map is the map from id to index.
  *@param clause is the in clause for the query.
  *@param list is the parameter list for the query.
  */
  protected void getDocumentIngestDataChunk(DocumentIngestStatus[] rval, Map<String,Integer> map, String outputConnectionName, List<String> list)
    throws ManifoldCFException
  {
    ArrayList newList = new ArrayList();
    String query = buildConjunctionClause(newList,new ClauseDescription[]{
      new MultiClause(docKeyField,list),
      new UnitaryClause(outputConnNameField,outputConnectionName)});
      
    // Get the primary records associated with this hash value
    IResultSet set = performQuery("SELECT "+idField+","+docKeyField+","+lastVersionField+","+lastOutputVersionField+","+authorityNameField+","+forcedParamsField+
      " FROM "+getTableName()+" WHERE "+query,newList,null,null);

    // Now, go through the original request once more, this time building the result
    for (int i = 0; i < set.getRowCount(); i++)
    {
      IResultRow row = set.getRow(i);
      String docHash = row.getValue(docKeyField).toString();
      Integer position = map.get(docHash);
      if (position != null)
      {
        Long id = (Long)row.getValue(idField);
        String lastVersion = (String)row.getValue(lastVersionField);
        if (lastVersion == null)
          lastVersion = "";
        String lastTransformationVersion = (String)row.getValue(lastTransformationVersionField);
        if (lastTransformationVersion == null)
          lastTransformationVersion = "";
        String lastOutputVersion = (String)row.getValue(lastOutputVersionField);
        if (lastOutputVersion == null)
          lastOutputVersion = "";
        String paramVersion = (String)row.getValue(forcedParamsField);
        if (paramVersion == null)
          paramVersion = "";
        String authorityName = (String)row.getValue(authorityNameField);
        if (authorityName == null)
          authorityName = "";
        int indexValue = position.intValue();
        rval[indexValue] = new DocumentIngestStatus(
          lastVersion,lastTransformationVersion,lastOutputVersion,paramVersion,authorityName);
      }
    }
  }

  // Protected methods

  /** Remove document, using the specified output connection, via the standard pool.
  */
  protected void removeDocument(IOutputConnection connection, String documentURI, String outputDescription, IOutputRemoveActivity activities)
    throws ManifoldCFException, ServiceInterruption
  {
    IOutputConnector connector = outputConnectorPool.grab(connection);
    if (connector == null)
      // The connector is not installed; treat this as a service interruption.
      throw new ServiceInterruption("Output connector not installed",0L);
    try
    {
      connector.removeDocument(documentURI,outputDescription,activities);
    }
    finally
    {
      outputConnectorPool.release(connection,connector);
    }
  }

  /** Make a key from a document class and a hash */
  protected static String makeKey(String documentClass, String documentHash)
  {
    return documentClass + ":" + documentHash;
  }

  /** This class contains the information necessary to delete a document */
  protected static class DeleteInfo
  {
    protected String uriValue;
    protected String outputVersion;

    public DeleteInfo(String uriValue, String outputVersion)
    {
      this.uriValue = uriValue;
      this.outputVersion = outputVersion;
    }

    public String getURI()
    {
      return uriValue;
    }

    public String getOutputVersion()
    {
      return outputVersion;
    }
  }
  
  /** Wrapper class for add activity.  This handles conversion of output connector activity logging to 
  * qualified activity names */
  protected static class OutputRecordingActivity implements IOutputHistoryActivity
  {
    protected final IOutputHistoryActivity activityProvider;
    protected final String outputConnectionName;
    
    public OutputRecordingActivity(IOutputHistoryActivity activityProvider, String outputConnectionName)
    {
      this.activityProvider = activityProvider;
      this.outputConnectionName = outputConnectionName;
    }
    
    /** Record time-stamped information about the activity of the output connector.
    *@param startTime is either null or the time since the start of epoch in milliseconds (Jan 1, 1970).  Every
    *       activity has an associated time; the startTime field records when the activity began.  A null value
    *       indicates that the start time and the finishing time are the same.
    *@param activityType is a string which is fully interpretable only in the context of the connector involved, which is
    *       used to categorize what kind of activity is being recorded.  For example, a web connector might record a
    *       "fetch document" activity.  Cannot be null.
    *@param dataSize is the number of bytes of data involved in the activity, or null if not applicable.
    *@param entityURI is a (possibly long) string which identifies the object involved in the history record.
    *       The interpretation of this field will differ from connector to connector.  May be null.
    *@param resultCode contains a terse description of the result of the activity.  The description is limited in
    *       size to 255 characters, and can be interpreted only in the context of the current connector.  May be null.
    *@param resultDescription is a (possibly long) human-readable string which adds detail, if required, to the result
    *       described in the resultCode field.  This field is not meant to be queried on.  May be null.
    */
    @Override
    public void recordActivity(Long startTime, String activityType, Long dataSize,
      String entityURI, String resultCode, String resultDescription)
      throws ManifoldCFException
    {
      activityProvider.recordActivity(startTime,ManifoldCF.qualifyOutputActivityName(activityType,outputConnectionName),
        dataSize,entityURI,resultCode,resultDescription);
    }

  }
  
  /** Wrapper class for add activity.  This handles conversion of transformation connector activity logging to 
  * qualified activity names */
  protected static class TransformationRecordingActivity implements IOutputHistoryActivity
  {
    protected final IOutputHistoryActivity activityProvider;
    protected final String transformationConnectionName;
    
    public TransformationRecordingActivity(IOutputHistoryActivity activityProvider, String transformationConnectionName)
    {
      this.activityProvider = activityProvider;
      this.transformationConnectionName = transformationConnectionName;
    }
    
    /** Record time-stamped information about the activity of the output connector.
    *@param startTime is either null or the time since the start of epoch in milliseconds (Jan 1, 1970).  Every
    *       activity has an associated time; the startTime field records when the activity began.  A null value
    *       indicates that the start time and the finishing time are the same.
    *@param activityType is a string which is fully interpretable only in the context of the connector involved, which is
    *       used to categorize what kind of activity is being recorded.  For example, a web connector might record a
    *       "fetch document" activity.  Cannot be null.
    *@param dataSize is the number of bytes of data involved in the activity, or null if not applicable.
    *@param entityURI is a (possibly long) string which identifies the object involved in the history record.
    *       The interpretation of this field will differ from connector to connector.  May be null.
    *@param resultCode contains a terse description of the result of the activity.  The description is limited in
    *       size to 255 characters, and can be interpreted only in the context of the current connector.  May be null.
    *@param resultDescription is a (possibly long) human-readable string which adds detail, if required, to the result
    *       described in the resultCode field.  This field is not meant to be queried on.  May be null.
    */
    @Override
    public void recordActivity(Long startTime, String activityType, Long dataSize,
      String entityURI, String resultCode, String resultDescription)
      throws ManifoldCFException
    {
      activityProvider.recordActivity(startTime,ManifoldCF.qualifyTransformationActivityName(activityType,transformationConnectionName),
        dataSize,entityURI,resultCode,resultDescription);
    }

  }

  protected static class OutputRemoveActivitiesWrapper extends OutputRecordingActivity implements IOutputRemoveActivity
  {
    protected final IOutputRemoveActivity removeActivities;
    
    public OutputRemoveActivitiesWrapper(IOutputRemoveActivity removeActivities, String outputConnectionName)
    {
      super(removeActivities,outputConnectionName);
      this.removeActivities = removeActivities;
    }

  }
  
  protected static class OutputAddActivitiesWrapper extends OutputRecordingActivity implements IOutputAddActivity
  {
    protected final IOutputAddActivity addActivities;
    
    public OutputAddActivitiesWrapper(IOutputAddActivity addActivities, String outputConnectionName)
    {
      super(addActivities,outputConnectionName);
      this.addActivities = addActivities;
    }
    
    /** Qualify an access token appropriately, to match access tokens as returned by mod_aa.  This method
    * includes the authority name with the access token, if any, so that each authority may establish its own token space.
    *@param authorityNameString is the name of the authority to use to qualify the access token.
    *@param accessToken is the raw, repository access token.
    *@return the properly qualified access token.
    */
    @Override
    public String qualifyAccessToken(String authorityNameString, String accessToken)
      throws ManifoldCFException
    {
      return addActivities.qualifyAccessToken(authorityNameString,accessToken);
    }

    /** Send a document via the pipeline to the next output connection.
    *@param documentURI is the document's URI.
    *@param document is the document data to be processed (handed to the output data store).
    *@param authorityNameString is the authority name string that should be used to qualify the document's access tokens.
    *@return the document status (accepted or permanently rejected); return codes are listed in IPipelineConnector.
    *@throws IOException only if there's an IO error reading the data from the document.
    */
    public int sendDocument(String documentURI, RepositoryDocument document, String authorityNameString)
      throws ManifoldCFException, ServiceInterruption, IOException
    {
      return addActivities.sendDocument(documentURI,document,authorityNameString);
    }

    /** Detect if a mime type is acceptable downstream or not.  This method is used to determine whether it makes sense to fetch a document
    * in the first place.
    *@param mimeType is the mime type of the document.
    *@return true if the mime type can be accepted by the downstream connection.
    */
    @Override
    public boolean checkMimeTypeIndexable(String mimeType)
      throws ManifoldCFException, ServiceInterruption
    {
      return addActivities.checkMimeTypeIndexable(mimeType);
    }

    /** Pre-determine whether a document (passed here as a File object) is acceptable downstream.  This method is
    * used to determine whether a document needs to be actually transferred.  This hook is provided mainly to support
    * search engines that only handle a small set of accepted file types.
    *@param localFile is the local file to check.
    *@return true if the file is acceptable by the downstream connection.
    */
    @Override
    public boolean checkDocumentIndexable(File localFile)
      throws ManifoldCFException, ServiceInterruption
    {
      return addActivities.checkDocumentIndexable(localFile);
    }

    /** Pre-determine whether a document's length is acceptable downstream.  This method is used
    * to determine whether to fetch a document in the first place.
    *@param length is the length of the document.
    *@return true if the file is acceptable by the downstream connection.
    */
    @Override
    public boolean checkLengthIndexable(long length)
      throws ManifoldCFException, ServiceInterruption
    {
      return addActivities.checkLengthIndexable(length);
    }

    /** Pre-determine whether a document's URL is acceptable downstream.  This method is used
    * to help filter out documents that cannot be indexed in advance.
    *@param url is the URL of the document.
    *@return true if the file is acceptable by the downstream connection.
    */
    @Override
    public boolean checkURLIndexable(String url)
      throws ManifoldCFException, ServiceInterruption
    {
      return addActivities.checkURLIndexable(url);
    }

  }
  
  protected static class OutputActivitiesWrapper extends OutputAddActivitiesWrapper implements IOutputActivity
  {
    protected final IOutputActivity activities;
    
    public OutputActivitiesWrapper(IOutputActivity activities, String outputConnectionName)
    {
      super(activities,outputConnectionName);
      this.activities = activities;
    }
  }
  
  protected class PipelineObject
  {
    public final PipelineConnections pipelineConnections;
    public final IOutputConnector[] outputConnectors;
    public final ITransformationConnector[] transformationConnectors;
    
    public PipelineObject(
      PipelineConnections pipelineConnections,
      ITransformationConnector[] transformationConnectors,
      IOutputConnector[] outputConnectors)
    {
      this.pipelineConnections = pipelineConnections;
      this.transformationConnectors = transformationConnectors;
      this.outputConnectors = outputConnectors;
    }
    
    public boolean checkMimeTypeIndexable(String mimeType, IOutputCheckActivity finalActivity)
      throws ManifoldCFException, ServiceInterruption
    {
      PipelineCheckFanout entryPoint = buildCheckPipeline(finalActivity);
      return entryPoint.checkMimeTypeIndexable(mimeType);
    }

    public boolean checkDocumentIndexable(File localFile, IOutputCheckActivity finalActivity)
      throws ManifoldCFException, ServiceInterruption
    {
      PipelineCheckFanout entryPoint = buildCheckPipeline(finalActivity);
      return entryPoint.checkDocumentIndexable(localFile);
    }

    public boolean checkLengthIndexable(long length, IOutputCheckActivity finalActivity)
      throws ManifoldCFException, ServiceInterruption
    {
      PipelineCheckFanout entryPoint = buildCheckPipeline(finalActivity);
      return entryPoint.checkLengthIndexable(length);
    }
    
    public boolean checkURLIndexable(String uri, IOutputCheckActivity finalActivity)
      throws ManifoldCFException, ServiceInterruption
    {
      PipelineCheckFanout entryPoint = buildCheckPipeline(finalActivity);
      return entryPoint.checkURLIndexable(uri);
    }

    public void release()
      throws ManifoldCFException
    {
      outputConnectorPool.releaseMultiple(pipelineConnections.getOutputConnections(),outputConnectors);
      transformationConnectorPool.releaseMultiple(pipelineConnections.getTransformationConnections(),transformationConnectors);
    }
    
    protected PipelineCheckFanout buildCheckPipeline(IOutputCheckActivity finalActivity)
    {
      // Algorithm for building a pipeline:
      // (1) We start with the set of final output connection stages, and build an entry point for each one.  That's our "current set".
      // (2) We cycle through the "current set".  For each member, we attempt to go upstream a level.
      // (3) Before we can build the pipeline activity class for the next upstream stage, we need to have present ALL of the children that share that
      //   parent.  If we don't have that yet, we throw the stage back into the list.
      // (4) We continue until there is one stage left that has no parent, and that's what we return.
      
      // Create the current set
      Map<Integer,PipelineCheckEntryPoint> currentSet = new HashMap<Integer,PipelineCheckEntryPoint>();
      // First, locate all the output stages, and enter them into the set
      IPipelineSpecification spec = pipelineConnections.getSpecification();
      IPipelineSpecificationBasic basicSpec = spec.getBasicPipelineSpecification();
      int count = basicSpec.getOutputCount();
      for (int i = 0; i < count; i++)
      {
        int outputStage = basicSpec.getOutputStage(i);
        PipelineCheckEntryPoint outputStageEntryPoint = new PipelineCheckEntryPoint(
          outputConnectors[pipelineConnections.getOutputConnectionIndex(outputStage).intValue()],
          spec.getStageDescriptionString(outputStage),finalActivity);
        currentSet.put(new Integer(outputStage), outputStageEntryPoint);
      }
      // Cycle through the "current set"
      while (true)
      {
        int parent = -1;
        int[] siblings = null;
        for (Integer outputStage : currentSet.keySet())
        {
          parent = basicSpec.getStageParent(outputStage.intValue());
          // Look up the children
          siblings = basicSpec.getStageChildren(parent);
          // Are all the siblings in the current set yet?  If not, we can't proceed with this entry.
          boolean skipToNext = false;
          for (int sibling : siblings)
          {
            if (currentSet.get(new Integer(sibling)) == null)
            {
              skipToNext = true;
              break;
            }
          }
          if (skipToNext)
          {
            siblings = null;
            continue;
          }
          // All siblings are present!
          break;
        }
        
        // Siblings will be set if there's a stage we can do.  If not, we're done, but this should already have been detected.
        if (siblings == null)
          throw new IllegalStateException("Not at root but can't progress");
        
        PipelineCheckEntryPoint[] siblingEntryPoints = new PipelineCheckEntryPoint[siblings.length];
        for (int j = 0; j < siblings.length; j++)
        {
          siblingEntryPoints[j] = currentSet.remove(new Integer(siblings[j]));
        }
        // Wrap the entry points in a fan-out class, which has pipe connector-like methods that fire across all the connectors.
        PipelineCheckFanout pcf = new PipelineCheckFanout(siblingEntryPoints);
        if (parent == -1)
          return pcf;
        PipelineCheckEntryPoint newEntry = new PipelineCheckEntryPoint(
          transformationConnectors[pipelineConnections.getTransformationConnectionIndex(parent).intValue()],
          spec.getStageDescriptionString(parent),pcf);
        currentSet.put(new Integer(parent), newEntry);
      }
    }
  }
  
  protected class PipelineObjectWithVersions extends PipelineObject
  {
    protected final PipelineConnectionsWithVersions pipelineConnectionsWithVersions;
    
    public PipelineObjectWithVersions(
      PipelineConnectionsWithVersions pipelineConnectionsWithVersions,
      ITransformationConnector[] transformationConnectors,
      IOutputConnector[] outputConnectors)
    {
      super(pipelineConnectionsWithVersions,transformationConnectors,outputConnectors);
      this.pipelineConnectionsWithVersions = pipelineConnectionsWithVersions;
    }

    public int addOrReplaceDocumentWithException(String docKey, String documentURI, RepositoryDocument document, String newDocumentVersion, String newParameterVersion, String authorityNameString, IOutputActivity finalActivity, long ingestTime)
      throws ManifoldCFException, ServiceInterruption, IOException
    {
      PipelineAddFanout entryPoint = buildAddPipeline(finalActivity,newDocumentVersion,newParameterVersion,authorityNameString,ingestTime,docKey);
      return entryPoint.sendDocument(documentURI,document,authorityNameString);
    }
    
    protected PipelineAddFanout buildAddPipeline(IOutputActivity finalActivity,
      String newDocumentVersion, String newParameterVersion, String newAuthorityNameString,
      long ingestTime, String docKey)
    {
      // Algorithm for building a pipeline:
      // (1) We start with the set of final output connection stages, and build an entry point for each one.  That's our "current set".
      // (2) We cycle through the "current set".  For each member, we attempt to go upstream a level.
      // (3) Before we can build the pipeline activity class for the next upstream stage, we need to have present ALL of the children that share that
      //   parent.  If we don't have that yet, we throw the stage back into the list.
      // (4) We continue until there is one stage left that has no parent, and that's what we return.
      
      // Create the current set
      Map<Integer,PipelineAddEntryPoint> currentSet = new HashMap<Integer,PipelineAddEntryPoint>();
      // First, locate all the output stages, and enter them into the set
      IPipelineSpecificationWithVersions fullSpec = pipelineConnectionsWithVersions.getSpecificationWithVersions();
      IPipelineSpecification pipelineSpec = fullSpec.getPipelineSpecification();
      IPipelineSpecificationBasic basicSpec = pipelineSpec.getBasicPipelineSpecification();
      
      int outputCount = basicSpec.getOutputCount();
      for (int i = 0; i < outputCount; i++)
      {
        int outputStage = basicSpec.getOutputStage(i);
        
        // Compute whether we need to reindex this record to this output or not, based on spec.
        String oldDocumentVersion = fullSpec.getOutputDocumentVersionString(i);
        String oldParameterVersion = fullSpec.getOutputParameterVersionString(i);
        String oldOutputVersion = fullSpec.getOutputVersionString(i);
        String oldTransformationVersion = fullSpec.getOutputTransformationVersionString(i);
        String oldAuthorityName = fullSpec.getAuthorityNameString(i);

        // Compute the transformation version string.  Must always be computed if we're going to reindex, since we save it.
        String newTransformationVersion = computePackedTransformationVersion(pipelineSpec,outputStage);
        
        boolean needToReindex = (oldDocumentVersion == null);
        if (needToReindex == false)
        {
          needToReindex = (!oldDocumentVersion.equals(newDocumentVersion) ||
            !oldParameterVersion.equals(newParameterVersion) ||
            !oldOutputVersion.equals(pipelineSpec.getStageDescriptionString(outputStage)) ||
            !oldAuthorityName.equals(newAuthorityNameString));
        }
        if (needToReindex == false)
        {
          needToReindex = (!oldTransformationVersion.equals(newTransformationVersion));
        }

        int connectionIndex = pipelineConnectionsWithVersions.getOutputConnectionIndex(outputStage).intValue();
        PipelineAddEntryPoint outputStageEntryPoint = new OutputAddEntryPoint(
          outputConnectors[connectionIndex],
          pipelineSpec.getStageDescriptionString(outputStage),
          new OutputActivitiesWrapper(finalActivity,basicSpec.getStageConnectionName(outputStage)),
          needToReindex,
          basicSpec.getStageConnectionName(outputStage),
          newTransformationVersion,
          ingestTime,
          newDocumentVersion,
          newParameterVersion,
          docKey);
        currentSet.put(new Integer(outputStage), outputStageEntryPoint);
      }
      // Cycle through the "current set"
      while (true)
      {
        int parent = -1;
        int[] siblings = null;
        for (Integer outputStage : currentSet.keySet())
        {
          parent = basicSpec.getStageParent(outputStage.intValue());
          // Look up the children
          siblings = basicSpec.getStageChildren(parent);
          // Are all the siblings in the current set yet?  If not, we can't proceed with this entry.
          boolean skipToNext = false;
          for (int sibling : siblings)
          {
            if (currentSet.get(new Integer(sibling)) == null)
            {
              skipToNext = true;
              break;
            }
          }
          if (skipToNext)
          {
            siblings = null;
            continue;
          }
          // All siblings are present!
          break;
        }
        
        // Siblings will be set if there's a stage we can do.  If not, we're done, but this should already have been detected.
        if (siblings == null)
          throw new IllegalStateException("Not at root but can't progress");
        
        PipelineAddEntryPoint[] siblingEntryPoints = new PipelineAddEntryPoint[siblings.length];
        for (int j = 0; j < siblings.length; j++)
        {
          siblingEntryPoints[j] = currentSet.remove(new Integer(siblings[j]));
        }
        // Wrap the entry points in a fan-out class, which has pipe connector-like methods that fire across all the connectors.
        PipelineAddFanout pcf = new PipelineAddFanout(siblingEntryPoints,
          (parent==-1)?null:new TransformationRecordingActivity(finalActivity,
            basicSpec.getStageConnectionName(parent)),
          finalActivity);
        if (parent == -1)
          return pcf;
        PipelineAddEntryPoint newEntry = new PipelineAddEntryPoint(
          transformationConnectors[pipelineConnections.getTransformationConnectionIndex(parent).intValue()],
          pipelineSpec.getStageDescriptionString(parent),pcf,pcf.checkNeedToReindex());
        currentSet.put(new Integer(parent), newEntry);
      }

    }

  }

  /** This class describes the entry stage of multiple siblings in a check pipeline.
  */
  public static class PipelineCheckFanout implements IOutputCheckActivity
  {
    protected final PipelineCheckEntryPoint[] entryPoints;
    
    public PipelineCheckFanout(PipelineCheckEntryPoint[] entryPoints)
    {
      this.entryPoints = entryPoints;
    }
    
    @Override
    public boolean checkMimeTypeIndexable(String mimeType)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineCheckEntryPoint p : entryPoints)
      {
        if (p.checkMimeTypeIndexable(mimeType))
          return true;
      }
      return false;
    }
    
    @Override
    public boolean checkDocumentIndexable(File localFile)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineCheckEntryPoint p : entryPoints)
      {
        if (p.checkDocumentIndexable(localFile))
          return true;
      }
      return false;
    }

    @Override
    public boolean checkLengthIndexable(long length)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineCheckEntryPoint p : entryPoints)
      {
        if (p.checkLengthIndexable(length))
          return true;
      }
      return false;
    }

    @Override
    public boolean checkURLIndexable(String uri)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineCheckEntryPoint p : entryPoints)
      {
        if (p.checkURLIndexable(uri))
          return true;
      }
      return false;
    }
  }
  
  /** This class describes the entry stage of a check pipeline.
  */
  public static class PipelineCheckEntryPoint
  {
    protected final IPipelineConnector pipelineConnector;
    protected final VersionContext pipelineDescriptionString;
    protected final IOutputCheckActivity checkActivity;
    
    public PipelineCheckEntryPoint(
      IPipelineConnector pipelineConnector,
      VersionContext pipelineDescriptionString,
      IOutputCheckActivity checkActivity)
    {
      this.pipelineConnector= pipelineConnector;
      this.pipelineDescriptionString = pipelineDescriptionString;
      this.checkActivity = checkActivity;
    }
    
    public boolean checkMimeTypeIndexable(String mimeType)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkMimeTypeIndexable(pipelineDescriptionString,mimeType,checkActivity);
    }
    
    public boolean checkDocumentIndexable(File localFile)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkDocumentIndexable(pipelineDescriptionString,localFile,checkActivity);
    }
    
    public boolean checkLengthIndexable(long length)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkLengthIndexable(pipelineDescriptionString,length,checkActivity);
    }

    public boolean checkURLIndexable(String uri)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkURLIndexable(pipelineDescriptionString,uri,checkActivity);
    }
    
  }
  
  /** This class describes the entry stage of multiple siblings in an add pipeline.
  */
  public static class PipelineAddFanout implements IOutputAddActivity
  {
    protected final PipelineAddEntryPoint[] entryPoints;
    protected final IOutputHistoryActivity finalHistoryActivity;
    protected final IOutputQualifyActivity finalQualifyActivity;

    public PipelineAddFanout(PipelineAddEntryPoint[] entryPoints, IOutputHistoryActivity finalHistoryActivity,
      IOutputQualifyActivity finalQualifyActivity)
    {
      this.entryPoints = entryPoints;
      this.finalHistoryActivity = finalHistoryActivity;
      this.finalQualifyActivity = finalQualifyActivity;
    }
    
    public boolean checkNeedToReindex()
    {
      // Look at the entry points, and make sure they're not all disabled.
      for (PipelineAddEntryPoint p : entryPoints)
      {
        if (p.isActive())
          return true;
      }
      return false;
    }
    
    @Override
    public boolean checkMimeTypeIndexable(String mimeType)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineAddEntryPoint p : entryPoints)
      {
        if (p.checkMimeTypeIndexable(mimeType))
          return true;
      }
      return false;
    }
    
    @Override
    public boolean checkDocumentIndexable(File localFile)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineAddEntryPoint p : entryPoints)
      {
        if (p.checkDocumentIndexable(localFile))
          return true;
      }
      return false;
    }

    @Override
    public boolean checkLengthIndexable(long length)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineAddEntryPoint p : entryPoints)
      {
        if (p.checkLengthIndexable(length))
          return true;
      }
      return false;
    }

    @Override
    public boolean checkURLIndexable(String uri)
      throws ManifoldCFException, ServiceInterruption
    {
      // OR all results
      for (PipelineAddEntryPoint p : entryPoints)
      {
        if (p.checkURLIndexable(uri))
          return true;
      }
      return false;
    }
    
    /** Send a document via the pipeline to the next output connection.
    *@param documentURI is the document's URI.
    *@param document is the document data to be processed (handed to the output data store).
    *@param authorityNameString is the authority name string that should be used to qualify the document's access tokens.
    *@return the document status (accepted or permanently rejected); return codes are listed in IPipelineConnector.
    *@throws IOException only if there's an IO error reading the data from the document.
    */
    public int sendDocument(String documentURI, RepositoryDocument document, String authorityNameString)
      throws ManifoldCFException, ServiceInterruption, IOException
    {
      // First, count the number of active entry points.
      int activeCount = 0;
      for (PipelineAddEntryPoint p : entryPoints)
      {
        if (p.isActive())
          activeCount++;
      }
      if (activeCount <= 1)
      {
        // No need to copy anything.
        int rval = IPipelineConnector.DOCUMENTSTATUS_REJECTED;
        for (PipelineAddEntryPoint p : entryPoints)
        {
          if (!p.isActive())
            continue;
          if (p.addOrReplaceDocumentWithException(documentURI,document,authorityNameString) == IPipelineConnector.DOCUMENTSTATUS_ACCEPTED)
            rval = IPipelineConnector.DOCUMENTSTATUS_ACCEPTED;
        }
        return rval;
      }
      else
      {
        // Create a RepositoryDocumentFactory, which we'll need to clean up at the end.
        RepositoryDocumentFactory factory = new RepositoryDocumentFactory(document);
        try
        {
          // If any of them accept the document, we return "accept".
          int rval = IPipelineConnector.DOCUMENTSTATUS_REJECTED;
          for (PipelineAddEntryPoint p : entryPoints)
          {
            if (!p.isActive())
              continue;
            if (p.addOrReplaceDocumentWithException(documentURI,factory.createDocument(),authorityNameString) == IPipelineConnector.DOCUMENTSTATUS_ACCEPTED)
              rval = IPipelineConnector.DOCUMENTSTATUS_ACCEPTED;
          }
          return rval;
        }
        finally
        {
          factory.close();
        }
      }
    }

    /** Qualify an access token appropriately, to match access tokens as returned by mod_aa.  This method
    * includes the authority name with the access token, if any, so that each authority may establish its own token space.
    *@param authorityNameString is the name of the authority to use to qualify the access token.
    *@param accessToken is the raw, repository access token.
    *@return the properly qualified access token.
    */
    @Override
    public String qualifyAccessToken(String authorityNameString, String accessToken)
      throws ManifoldCFException
    {
      // This functionality does not need to be staged; we just want to vector through to the final stage directly.
      return finalQualifyActivity.qualifyAccessToken(authorityNameString,accessToken);
    }

    /** Record time-stamped information about the activity of the output connector.
    *@param startTime is either null or the time since the start of epoch in milliseconds (Jan 1, 1970).  Every
    *       activity has an associated time; the startTime field records when the activity began.  A null value
    *       indicates that the start time and the finishing time are the same.
    *@param activityType is a string which is fully interpretable only in the context of the connector involved, which is
    *       used to categorize what kind of activity is being recorded.  For example, a web connector might record a
    *       "fetch document" activity.  Cannot be null.
    *@param dataSize is the number of bytes of data involved in the activity, or null if not applicable.
    *@param entityURI is a (possibly long) string which identifies the object involved in the history record.
    *       The interpretation of this field will differ from connector to connector.  May be null.
    *@param resultCode contains a terse description of the result of the activity.  The description is limited in
    *       size to 255 characters, and can be interpreted only in the context of the current connector.  May be null.
    *@param resultDescription is a (possibly long) human-readable string which adds detail, if required, to the result
    *       described in the resultCode field.  This field is not meant to be queried on.  May be null.
    */
    @Override
    public void recordActivity(Long startTime, String activityType, Long dataSize,
      String entityURI, String resultCode, String resultDescription)
      throws ManifoldCFException
    {
      // Each stage of the pipeline uses a specific activity for recording history, but it's not fundamentally
      // pipelined
      finalHistoryActivity.recordActivity(startTime,activityType,dataSize,entityURI,resultCode,resultDescription);
    }
  }

  /** This class describes the entry stage of an add pipeline.
  */
  public static class PipelineAddEntryPoint
  {
    protected final IPipelineConnector pipelineConnector;
    protected final VersionContext pipelineDescriptionString;
    protected final IOutputAddActivity addActivity;
    protected final boolean isActive;
    
    public PipelineAddEntryPoint(IPipelineConnector pipelineConnector,
      VersionContext pipelineDescriptionString,
      IOutputAddActivity addActivity,
      boolean isActive)
    {
      this.pipelineConnector = pipelineConnector;
      this.pipelineDescriptionString = pipelineDescriptionString;
      this.addActivity = addActivity;
      this.isActive = isActive;
    }
    
    public boolean isActive()
    {
      return isActive;
    }
    
    public boolean checkMimeTypeIndexable(String mimeType)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkMimeTypeIndexable(pipelineDescriptionString,mimeType,addActivity);
    }
    
    public boolean checkDocumentIndexable(File localFile)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkDocumentIndexable(pipelineDescriptionString,localFile,addActivity);
    }
    
    public boolean checkLengthIndexable(long length)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkLengthIndexable(pipelineDescriptionString,length,addActivity);
    }

    public boolean checkURLIndexable(String uri)
      throws ManifoldCFException, ServiceInterruption
    {
      return pipelineConnector.checkURLIndexable(pipelineDescriptionString,uri,addActivity);
    }

    public int addOrReplaceDocumentWithException(String documentURI, RepositoryDocument document, String authorityNameString)
      throws ManifoldCFException, ServiceInterruption, IOException
    {
      return pipelineConnector.addOrReplaceDocumentWithException(
        documentURI,pipelineDescriptionString,
        document,authorityNameString,addActivity);
    }
  }
  
  public class OutputAddEntryPoint extends PipelineAddEntryPoint
  {
    protected final IOutputConnector outputConnector;
    protected final String outputConnectionName;
    protected final String transformationVersion;
    protected final long ingestTime;
    protected final String documentVersion;
    protected final String parameterVersion;
    protected final String docKey;
    protected final IOutputActivity activity;
    
    public OutputAddEntryPoint(IOutputConnector outputConnector,
      VersionContext outputDescriptionString,
      IOutputActivity activity,
      boolean isActive,
      String outputConnectionName,
      String transformationVersion,
      long ingestTime,
      String documentVersion,
      String parameterVersion,
      String docKey)
    {
      super(outputConnector,outputDescriptionString,activity,isActive);
      this.outputConnector = outputConnector;
      this.outputConnectionName = outputConnectionName;
      this.transformationVersion = transformationVersion;
      this.ingestTime = ingestTime;
      this.documentVersion = documentVersion;
      this.parameterVersion = parameterVersion;
      this.docKey = docKey;
      this.activity = activity;
    }
    
    @Override
    public int addOrReplaceDocumentWithException(String documentURI, RepositoryDocument document, String authorityNameString)
      throws ManifoldCFException, ServiceInterruption, IOException
    {
      // No transactions; not safe because post may take too much time

      // First, calculate a document uri hash value
      String documentURIHash = null;
      if (documentURI != null)
        documentURIHash = ManifoldCF.hash(documentURI);

      String oldURI = null;
      String oldURIHash = null;
      String oldOutputVersion = null;

      // Repeat if needed
      while (true)
      {
        long sleepAmt = 0L;
        try
        {
          // See what uri was used before for this doc, if any
          ArrayList list = new ArrayList();
          String query = buildConjunctionClause(list,new ClauseDescription[]{
            new UnitaryClause(docKeyField,docKey),
            new UnitaryClause(outputConnNameField,outputConnectionName)});
            
          IResultSet set = performQuery("SELECT "+docURIField+","+uriHashField+","+lastOutputVersionField+" FROM "+getTableName()+
            " WHERE "+query,list,null,null);

          if (set.getRowCount() > 0)
          {
            IResultRow row = set.getRow(0);
            oldURI = (String)row.getValue(docURIField);
            oldURIHash = (String)row.getValue(uriHashField);
            oldOutputVersion = (String)row.getValue(lastOutputVersionField);
          }
          
          break;
        }
        catch (ManifoldCFException e)
        {
          // Look for deadlock and retry if so
          if (e.getErrorCode() == e.DATABASE_TRANSACTION_ABORT)
          {
            if (Logging.perf.isDebugEnabled())
              Logging.perf.debug("Aborted select looking for status: "+e.getMessage());
            sleepAmt = getSleepAmt();
            continue;
          }
          throw e;
        }
        finally
        {
          sleepFor(sleepAmt);
        }
      }

      // If uri hashes collide, then we must be sure to eliminate only the *correct* records from the table, or we will leave
      // dangling documents around.  So, all uri searches and comparisons MUST compare the actual uri as well.

      // But, since we need to insure that any given URI is only worked on by one thread at a time, use critical sections
      // to block the rare case that multiple threads try to work on the same URI.
      
      String[] lockArray = computeLockArray(documentURI,oldURI,outputConnectionName);
      lockManager.enterLocks(null,null,lockArray);
      try
      {

        ArrayList list = new ArrayList();
        
        if (oldURI != null && (documentURI == null || !oldURI.equals(documentURI)))
        {
          // Delete all records from the database that match the old URI, except for THIS record.
          list.clear();
          String query = buildConjunctionClause(list,new ClauseDescription[]{
            new UnitaryClause(uriHashField,"=",oldURIHash),
            new UnitaryClause(outputConnNameField,outputConnectionName)});
          list.add(docKey);
          performDelete("WHERE "+query+" AND "+docKeyField+"!=?",list,null);
          outputConnector.removeDocument(oldURI,oldOutputVersion,activity);
        }

        if (documentURI != null)
        {
          // Get rid of all records that match the NEW uri, except for this record.
          list.clear();
          String query = buildConjunctionClause(list,new ClauseDescription[]{
            new UnitaryClause(uriHashField,"=",documentURIHash),
            new UnitaryClause(outputConnNameField,outputConnectionName)});
          list.add(docKey);
          performDelete("WHERE "+query+" AND "+ docKeyField+"!=?",list,null);

          // Now, we know we are ready for the ingest.
        
          // Here are the cases:
          // 1) There was a service interruption before the upload started.
          // (In that case, we don't need to log anything, just reschedule).
          // 2) There was a service interruption after the document was transmitted.
          // (In that case, we should presume that the document was ingested, but
          //  reschedule another import anyway.)
          // 3) Everything went OK
          // (need to log the ingestion.)
          // 4) Everything went OK, but we were told we have an illegal document.
          // (We note the ingestion because if we don't we will be forced to repeat ourselves.
          //  In theory, document doesn't need to be deleted, but there is no way to signal
          //  that at the moment.)

          // Note an ingestion before we actually try it.
          // This is a marker that says "something is there"; it has an empty version, which indicates
          // that we don't know anything about it.  That means it will be reingested when the
          // next version comes along, and will be deleted if called for also.
          noteDocumentIngest(outputConnectionName,docKey,null,null,null,null,null,ingestTime,documentURI,documentURIHash);
          int result = super.addOrReplaceDocumentWithException(documentURI, document, authorityNameString);
          noteDocumentIngest(outputConnectionName,docKey,documentVersion,transformationVersion,pipelineDescriptionString.getVersionString(),parameterVersion,authorityNameString,ingestTime,documentURI,documentURIHash);
          return result;
        }

        // If we get here, it means we are noting that the document was examined, but that no change was required.  This is signaled
        // to noteDocumentIngest by having the null documentURI.
        noteDocumentIngest(outputConnectionName,docKey,documentVersion,transformationVersion,pipelineDescriptionString.getVersionString(),parameterVersion,authorityNameString,ingestTime,null,null);
        return IPipelineConnector.DOCUMENTSTATUS_ACCEPTED;
      }
      finally
      {
        lockManager.leaveLocks(null,null,lockArray);
      }
    }
  }

  protected static String[] computeLockArray(String documentURI, String oldURI, String outputConnectionName)
  {
    int uriCount = 0;
    if (documentURI != null)
      uriCount++;
    if (oldURI != null && (documentURI == null || !documentURI.equals(oldURI)))
      uriCount++;
    String[] lockArray = new String[uriCount];
    uriCount = 0;
    if (documentURI != null)
      lockArray[uriCount++] = outputConnectionName+":"+documentURI;
    if (oldURI != null && (documentURI == null || !documentURI.equals(oldURI)))
      lockArray[uriCount++] = outputConnectionName+":"+oldURI;
    return lockArray;
  }
  
  /** Basic pipeline specification for backwards-compatible methods */
  protected static class RuntPipelineSpecificationBasic implements IPipelineSpecificationBasic
  {
    protected final String outputConnectionName;
    
    public RuntPipelineSpecificationBasic(String outputConnectionName)
    {
      this.outputConnectionName = outputConnectionName;
    }
    
    /** Get a count of all stages.
    *@return the total count of all stages.
    */
    @Override
    public int getStageCount()
    {
      return 1;
    }

    /** Find children of a given pipeline stage.  Pass -1 to find the children of the root stage.
    *@param stage is the stage index to get the children of.
    *@return the pipeline stages that represent those children.
    */
    @Override
    public int[] getStageChildren(int stage)
    {
      if (stage == -1)
        return new int[]{0};
      return new int[0];
    }

    /** Find parent of a given pipeline stage.  Returns -1 if there's no parent (it's the root).
    *@param stage is the stage index to get the parent of.
    *@return the pipeline stage that is the parent, or -1.
    */
    @Override
    public int getStageParent(int stage)
    {
      return -1;
    }

    /** Get the connection name for a pipeline stage.
    *@param stage is the stage to get the connection name for.
    *@return the connection name for that stage.
    */
    @Override
    public String getStageConnectionName(int stage)
    {
      if (stage == 0)
        return outputConnectionName;
      return null;
    }

    /** Check if a stage is an output stage.
    *@param stage is the stage to check.
    *@return true if the stage represents an output connection.
    */
    @Override
    public boolean checkStageOutputConnection(int stage)
    {
      return true;
    }

    /** Return the number of output connections.
    *@return the total number of output connections in this specification.
    */
    @Override
    public int getOutputCount()
    {
      return 1;
    }
    
    /** Given an output index, return the stage number for that output.
    *@param index is the output connection index.
    *@return the stage number.
    */
    @Override
    public int getOutputStage(int index)
    {
      return 0;
    }

  }
  
  /** Pipeline specification for backwards-compatible methods without pipelines */
  protected static class RuntPipelineSpecification extends RuntPipelineSpecificationBasic implements IPipelineSpecification
  {
    protected final VersionContext outputDescriptionString;
    
    public RuntPipelineSpecification(String outputConnectionName, VersionContext outputDescriptionString)
    {
      super(outputConnectionName);
      this.outputDescriptionString = outputDescriptionString;
    }

    /** Get the basic pipeline specification.
    *@return the specification.
    */
    @Override
    public IPipelineSpecificationBasic getBasicPipelineSpecification()
    {
      return this;
    }

    /** Get the description string for a pipeline stage.
    *@param stage is the stage to get the connection name for.
    *@return the description string that stage.
    */
    @Override
    public VersionContext getStageDescriptionString(int stage)
    {
      if (stage == 0)
        return outputDescriptionString;
      return null;
    }

  }

  /** Pipeline specification for backwards-compatible methods without pipelines */
  protected static class RuntPipelineSpecificationWithVersions extends RuntPipelineSpecification implements IPipelineSpecificationWithVersions
  {
    protected final String oldDocumentVersion;
    protected final String oldParameterVersion;
    protected final String oldOutputVersion;
    protected final String oldTransformationVersion;
    protected final String oldAuthorityNameString;
    
    public RuntPipelineSpecificationWithVersions(String outputConnectionName, VersionContext outputDescriptionString,
      String oldDocumentVersion, String oldParameterVersion, String oldOutputVersion, String oldTransformationVersion,
      String oldAuthorityNameString)
    {
      super(outputConnectionName,outputDescriptionString);
      this.oldDocumentVersion = oldDocumentVersion;
      this.oldParameterVersion = oldParameterVersion;
      this.oldOutputVersion = oldOutputVersion;
      this.oldTransformationVersion = oldTransformationVersion;
      this.oldAuthorityNameString = oldAuthorityNameString;
    }

    /** Get pipeline specification.
    *@return the pipeline specification.
    */
    @Override
    public IPipelineSpecification getPipelineSpecification()
    {
      return this;
    }

    /** For a given output index, return a document version string.
    *@param index is the output index.
    *@return the document version string.
    */
    @Override
    public String getOutputDocumentVersionString(int index)
    {
      if (index == 0)
        return oldDocumentVersion;
      return null;
    }
    
    /** For a given output index, return a parameter version string.
    *@param index is the output index.
    *@return the parameter version string.
    */
    @Override
    public String getOutputParameterVersionString(int index)
    {
      if (index == 0)
        return oldParameterVersion;
      return null;
    }

    /** For a given output index, return a transformation version string.
    *@param index is the output index.
    *@return the transformation version string.
    */
    @Override
    public String getOutputTransformationVersionString(int index)
    {
      if (index == 0)
        return oldTransformationVersion;
      return null;
    }

    /** For a given output index, return an output version string.
    *@param index is the output index.
    *@return the output version string.
    */
    @Override
    public String getOutputVersionString(int index)
    {
      if (index == 0)
        return oldOutputVersion;
      return null;
    }
    
    /** For a given output index, return an authority name string.
    *@param index is the output index.
    *@return the authority name string.
    */
    @Override
    public String getAuthorityNameString(int index)
    {
      if (index == 0)
        return oldAuthorityNameString;
      return null;
    }

  }
  
  /** This class caches loaded connections corresponding to a pipeline specification.
  */
  protected class PipelineConnections
  {
    protected final IPipelineSpecification spec;
    protected final String[] transformationConnectionNames;
    protected final ITransformationConnection[] transformationConnections;
    protected final String[] outputConnectionNames;
    protected final IOutputConnection[] outputConnections;
    // We need a way to get from stage index to connection index.
    // These arrays are looked up by stage index, and return the appropriate connection index.
    protected final Map<Integer,Integer> transformationConnectionLookupMap = new HashMap<Integer,Integer>();
    protected final Map<Integer,Integer> outputConnectionLookupMap = new HashMap<Integer,Integer>();
    
    public PipelineConnections(IPipelineSpecification spec)
      throws ManifoldCFException
    {
      this.spec = spec;
      IPipelineSpecificationBasic basicSpec = spec.getBasicPipelineSpecification();
      // Now, load all the connections we'll ever need, being sure to only load one copy of each.
      // We first segregate them into unique transformation and output connections.
      int count = basicSpec.getStageCount();
      Set<String> transformations = new HashSet<String>();
      Set<String> outputs = new HashSet<String>();
      for (int i = 0; i < count; i++)
      {
        if (basicSpec.checkStageOutputConnection(i))
          outputs.add(basicSpec.getStageConnectionName(i));
        else
          transformations.add(basicSpec.getStageConnectionName(i));
      }
      
      Map<String,Integer> transformationNameMap = new HashMap<String,Integer>();
      Map<String,Integer> outputNameMap = new HashMap<String,Integer>();
      transformationConnectionNames = new String[transformations.size()];
      outputConnectionNames = new String[outputs.size()];
      int index = 0;
      for (String connectionName : transformations)
      {
        transformationConnectionNames[index] = connectionName;
        transformationNameMap.put(connectionName,new Integer(index++));
      }
      index = 0;
      for (String connectionName : outputs)
      {
        outputConnectionNames[index] = connectionName;
        outputNameMap.put(connectionName,new Integer(index++));
      }
      // Load!
      transformationConnections = transformationConnectionManager.loadMultiple(transformationConnectionNames);
      outputConnections = connectionManager.loadMultiple(outputConnectionNames);
      
      for (int i = 0; i < count; i++)
      {
        Integer k;
        if (basicSpec.checkStageOutputConnection(i))
        {
          outputConnectionLookupMap.put(new Integer(i),outputNameMap.get(basicSpec.getStageConnectionName(i)));
        }
        else
        {
          transformationConnectionLookupMap.put(new Integer(i),transformationNameMap.get(basicSpec.getStageConnectionName(i)));
        }
      }
    }
    
    public IPipelineSpecification getSpecification()
    {
      return spec;
    }
    
    public String[] getTransformationConnectionNames()
    {
      return transformationConnectionNames;
    }
    
    public ITransformationConnection[] getTransformationConnections()
    {
      return transformationConnections;
    }
    
    public String[] getOutputConnectionNames()
    {
      return outputConnectionNames;
    }
    
    public IOutputConnection[] getOutputConnections()
    {
      return outputConnections;
    }
    
    public Integer getTransformationConnectionIndex(int stage)
    {
      return transformationConnectionLookupMap.get(new Integer(stage));
    }
    
    public Integer getOutputConnectionIndex(int stage)
    {
      return outputConnectionLookupMap.get(new Integer(stage));
    }
    
  }

  protected class PipelineConnectionsWithVersions extends PipelineConnections
  {
    protected final IPipelineSpecificationWithVersions pipelineSpecificationWithVersions;
    
    public PipelineConnectionsWithVersions(IPipelineSpecificationWithVersions pipelineSpecificationWithVersions)
      throws ManifoldCFException
    {
      super(pipelineSpecificationWithVersions.getPipelineSpecification());
      this.pipelineSpecificationWithVersions = pipelineSpecificationWithVersions;
    }
    
    public IPipelineSpecificationWithVersions getSpecificationWithVersions()
    {
      return pipelineSpecificationWithVersions;
    }
    
  }
}
