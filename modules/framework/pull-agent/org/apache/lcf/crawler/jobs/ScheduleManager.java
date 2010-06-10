/* $Id$ */

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
package org.apache.lcf.crawler.jobs;

import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.agents.interfaces.*;
import org.apache.lcf.crawler.interfaces.*;
import java.util.*;

/** This class manages the "schedules" table, which contains the automatic execution schedule for each job.
* It's separated from the main jobs table because we will need multiple timeslots per job.
*/
public class ScheduleManager extends org.apache.lcf.core.database.BaseTable
{
  public static final String _rcsid = "@(#)$Id$";

  // Schema
  public final static String ownerIDField = "ownerid";
  public final static String ordinalField = "ordinal";
  public final static String dayOfWeekField = "dayofweek";
  public final static String dayOfMonthField = "dayofmonth";
  public final static String monthOfYearField = "monthofyear";
  public final static String yearField = "yearlist";
  public final static String hourOfDayField = "hourofday";
  public final static String minutesOfHourField = "minutesofhour";
  public final static String timezoneField = "timezone";
  public final static String windowDurationField = "windowlength";


  /** Constructor.
  *@param threadContext is the thread context.
  *@param database is the database instance.
  */
  public ScheduleManager(IThreadContext threadContext, IDBInterface database)
    throws LCFException
  {
    super(database,"schedules");
  }

  /** Install or upgrade.
  *@param ownerTable is the name of the table that owns this one.
  *@param owningTablePrimaryKey is the primary key of the owning table.
  */
  public void install(String ownerTable, String owningTablePrimaryKey)
    throws LCFException
  {
    // Standard practice: Outer loop to support upgrades
    while (true)
    {
      Map existing = getTableSchema(null,null);
      if (existing == null)
      {
        HashMap map = new HashMap();
        map.put(ownerIDField,new ColumnDescription("BIGINT",false,false,ownerTable,owningTablePrimaryKey,false));
        map.put(ordinalField,new ColumnDescription("BIGINT",false,false,null,null,false));
        map.put(dayOfWeekField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
        map.put(dayOfMonthField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
        map.put(monthOfYearField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
        map.put(yearField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
        map.put(hourOfDayField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
        map.put(minutesOfHourField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
        map.put(timezoneField,new ColumnDescription("VARCHAR(32)",false,true,null,null,false));
        map.put(windowDurationField,new ColumnDescription("BIGINT",false,true,null,null,false));
        performCreate(map,null);
      }
      else
      {
        // Upgrade code goes here, if needed.
        if (existing.get(yearField) == null)
        {
          // Need to rename the "year" column as the "yearlist" column.
          HashMap map = new HashMap();
          map.put(yearField,new ColumnDescription("VARCHAR(255)",false,true,null,null,false));
          performAlter(map,null,null,null);
          performModification("UPDATE "+getTableName()+" SET "+yearField+"=year",null,null);
          ArrayList list = new ArrayList();
          list.add("year");
          performAlter(null,null,list,null);

        }
      }

      // Index management
      IndexDescription ownerIndex = new IndexDescription(false,new String[]{ownerIDField});

      // Get rid of indexes that shouldn't be there
      Map indexes = getTableIndexes(null,null);
      Iterator iter = indexes.keySet().iterator();
      while (iter.hasNext())
      {
        String indexName = (String)iter.next();
        IndexDescription id = (IndexDescription)indexes.get(indexName);

        if (ownerIndex != null && id.equals(ownerIndex))
          ownerIndex = null;
        else if (indexName.indexOf("_pkey") == -1)
          // This index shouldn't be here; drop it
          performRemoveIndex(indexName);
      }

      // Add the ones we didn't find
      if (ownerIndex != null)
        performAddIndex(null,ownerIndex);

      break;
    }
  }

  /** Uninstall.
  */
  public void deinstall()
    throws LCFException
  {
    performDrop(null);
  }

  /** Fill in a set of schedules corresponding to a set of owner id's.
  *@param returnValues is a map keyed by ownerID, with value of JobDescription.
  *@param ownerIDList is the list of owner id's.
  *@param ownerIDParams is the corresponding set of owner id parameters.
  */
  public void getRows(Map returnValues, String ownerIDList, ArrayList ownerIDParams)
    throws LCFException
  {
    IResultSet set = performQuery("SELECT * FROM "+getTableName()+" WHERE "+ownerIDField+" IN ("+ownerIDList+") ORDER BY "+ordinalField+" ASC",ownerIDParams,
      null,null);
    int i = 0;
    while (i < set.getRowCount())
    {
      IResultRow row = set.getRow(i);
      Long ownerID = (Long)row.getValue(ownerIDField);
      ScheduleRecord sr = new ScheduleRecord(stringToEnumeratedValue((String)row.getValue(dayOfWeekField)),
        stringToEnumeratedValue((String)row.getValue(monthOfYearField)),
        stringToEnumeratedValue((String)row.getValue(dayOfMonthField)),
        stringToEnumeratedValue((String)row.getValue(yearField)),
        stringToEnumeratedValue((String)row.getValue(hourOfDayField)),
        stringToEnumeratedValue((String)row.getValue(minutesOfHourField)),
        (String)row.getValue(timezoneField),
        (Long)row.getValue(windowDurationField));
      ((JobDescription)returnValues.get(ownerID)).addScheduleRecord(sr);
      i++;
    }
  }

  /** Fill in a set of schedules corresponding to a set of owner id's.
  *@param returnValues is a map keyed by ownerID, with a value that is an ArrayList of ScheduleRecord objects.
  *@param ownerIDList is the list of owner id's.
  *@param ownerIDParams is the corresponding set of owner id parameters.
  */
  public void getRowsAlternate(Map returnValues, String ownerIDList, ArrayList ownerIDParams)
    throws LCFException
  {
    IResultSet set = performQuery("SELECT * FROM "+getTableName()+" WHERE "+ownerIDField+" IN ("+ownerIDList+") ORDER BY "+ordinalField+" ASC",ownerIDParams,
      null,null);
    int i = 0;
    while (i < set.getRowCount())
    {
      IResultRow row = set.getRow(i);
      Long ownerID = (Long)row.getValue(ownerIDField);
      ScheduleRecord sr = new ScheduleRecord(stringToEnumeratedValue((String)row.getValue(dayOfWeekField)),
        stringToEnumeratedValue((String)row.getValue(monthOfYearField)),
        stringToEnumeratedValue((String)row.getValue(dayOfMonthField)),
        stringToEnumeratedValue((String)row.getValue(yearField)),
        stringToEnumeratedValue((String)row.getValue(hourOfDayField)),
        stringToEnumeratedValue((String)row.getValue(minutesOfHourField)),
        (String)row.getValue(timezoneField),
        (Long)row.getValue(windowDurationField));
      ArrayList theList = (ArrayList)returnValues.get(ownerID);
      if (theList == null)
      {
        theList = new ArrayList();
        returnValues.put(ownerID,theList);
      }
      theList.add(sr);
      i++;
    }
  }

  /** Write a schedule list into the database.
  *@param ownerID is the owning identifier.
  *@param list is the job description that is the source of the schedule.
  */
  public void writeRows(Long ownerID, IJobDescription list)
    throws LCFException
  {
    beginTransaction();
    try
    {
      int i = 0;
      HashMap map = new HashMap();
      while (i < list.getScheduleRecordCount())
      {
        ScheduleRecord record = list.getScheduleRecord(i);
        map.clear();
        map.put(dayOfWeekField,enumeratedValueToString(record.getDayOfWeek()));
        map.put(monthOfYearField,enumeratedValueToString(record.getMonthOfYear()));
        map.put(dayOfMonthField,enumeratedValueToString(record.getDayOfMonth()));
        map.put(yearField,enumeratedValueToString(record.getYear()));
        map.put(hourOfDayField,enumeratedValueToString(record.getHourOfDay()));
        map.put(minutesOfHourField,enumeratedValueToString(record.getMinutesOfHour()));
        map.put(timezoneField,record.getTimezone());
        map.put(windowDurationField,record.getDuration());
        map.put(ownerIDField,ownerID);
        map.put(ordinalField,new Long((long)i));
        performInsert(map,null);
        i++;
      }
    }
    catch (LCFException e)
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

  /** Delete rows.
  *@param ownerID is the owner whose rows to delete.
  */
  public void deleteRows(Long ownerID)
    throws LCFException
  {
    ArrayList list = new ArrayList();
    list.add(ownerID);
    performDelete("WHERE "+ownerIDField+"=?",list,null);
  }

  /** Go from string to enumerated value.
  *@param value is the input.
  *@return the enumerated value.
  */
  public static EnumeratedValues stringToEnumeratedValue(String value)
    throws LCFException
  {
    if (value == null)
      return null;
    try
    {
      ArrayList valStore = new ArrayList();
      if (!value.equals("*"))
      {
        int curpos = 0;
        while (true)
        {
          int newpos = value.indexOf(",",curpos);
          if (newpos == -1)
          {
            valStore.add(new Integer(value.substring(curpos)));
            break;
          }
          valStore.add(new Integer(value.substring(curpos,newpos)));
          curpos = newpos+1;
        }
      }
      return new EnumeratedValues(valStore);
    }
    catch (NumberFormatException e)
    {
      throw new LCFException("Bad number: '"+value+"'",e);
    }

  }

  /** Go from enumerated value to string.
  *@param values is the enumerated value.
  *@return the string value.
  */
  public static String enumeratedValueToString(EnumeratedValues values)
  {
    if (values == null)
      return null;
    if (values.size() == 0)
      return "*";
    StringBuffer rval = new StringBuffer();
    Iterator iter = values.getValues();
    boolean first = true;
    while (iter.hasNext())
    {
      if (first)
        first = false;
      else
        rval.append(',');
      rval.append(((Integer)iter.next()).toString());
    }
    return rval.toString();
  }


}
