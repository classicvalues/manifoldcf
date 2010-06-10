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
package org.apache.lcf.core;

import java.io.*;
import org.apache.lcf.core.interfaces.*;
import org.apache.lcf.core.system.*;

public class DBDrop
{
  public static final String _rcsid = "@(#)$Id$";

  private DBDrop()
  {
  }


  public static void main(String[] args)
  {
    if (args.length != 1 && args.length != 2)
    {
      System.err.println("Usage: DBCreate <dbuser> [<dbpassword>]");
      System.exit(1);
    }

    String userName = args[0];
    String password = "";
    if (args.length == 2)
      password = args[1];

    try
    {
      LCF.initializeEnvironment();
      IThreadContext tc = ThreadContextFactory.make();
      LCF.dropSystemDatabase(tc,userName,password);
      System.err.println("LCF database dropped");
    }
    catch (LCFException e)
    {
      e.printStackTrace();
      System.exit(1);
    }
  }




}
