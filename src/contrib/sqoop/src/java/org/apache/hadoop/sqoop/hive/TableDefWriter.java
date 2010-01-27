/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.sqoop.hive;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.manager.ConnManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Creates (Hive-specific) SQL DDL statements to create tables to hold data
 * we're importing from another source.
 *
 * After we import the database into HDFS, we can inject it into Hive using
 * the CREATE TABLE and LOAD DATA INPATH statements generated by this object.
 */
public class TableDefWriter {

  public static final Log LOG = LogFactory.getLog(TableDefWriter.class.getName());

  private SqoopOptions options;
  private ConnManager connManager;
  private Configuration configuration;
  private String inputTableName;
  private String outputTableName;
  private boolean commentsEnabled;

  /**
   * Creates a new TableDefWriter to generate a Hive CREATE TABLE statement.
   * @param opts program-wide options
   * @param connMgr the connection manager used to describe the table.
   * @param table the name of the table to read.
   * @param config the Hadoop configuration to use to connect to the dfs
   * @param withComments if true, then tables will be created with a
   *        timestamp comment.
   */
  public TableDefWriter(final SqoopOptions opts, final ConnManager connMgr,
      final String inputTable, final String outputTable,
      final Configuration config, final boolean withComments) {
    this.options = opts;
    this.connManager = connMgr;
    this.inputTableName = inputTable;
    this.outputTableName = outputTable;
    this.configuration = config;
    this.commentsEnabled = withComments;
  }

  private Map<String, Integer> externalColTypes;

  /**
   * Set the column type map to be used.
   * (dependency injection for testing; not used in production.)
   */
  void setColumnTypes(Map<String, Integer> colTypes) {
    this.externalColTypes = colTypes;
    LOG.debug("Using test-controlled type map");
  }

  /**
   * Get the column names to import.
   */
  private String [] getColumnNames() {
    String [] colNames = options.getColumns();
    if (null != colNames) {
      return colNames; // user-specified column names.
    } else if (null != externalColTypes) {
      // Test-injection column mapping. Extract the col names from this.
      ArrayList<String> keyList = new ArrayList<String>();
      for (String key : externalColTypes.keySet()) {
        keyList.add(key);
      }

      return keyList.toArray(new String[keyList.size()]);
    } else {
      return connManager.getColumnNames(inputTableName);
    }
  }

  /**
   * @return the CREATE TABLE statement for the table to load into hive.
   */
  public String getCreateTableStmt() throws IOException {
    Map<String, Integer> columnTypes;

    if (externalColTypes != null) {
      // Use pre-defined column types.
      columnTypes = externalColTypes;
    } else {
      // Get these from the database.
      columnTypes = connManager.getColumnTypes(inputTableName);
    }

    String [] colNames = getColumnNames();
    StringBuilder sb = new StringBuilder();
    sb.append("CREATE TABLE " + outputTableName + " ( ");

    boolean first = true;
    for (String col : colNames) {
      if (!first) {
        sb.append(", ");
      }

      first = false;

      Integer colType = columnTypes.get(col);
      String hiveColType = connManager.toHiveType(colType);
      if (null == hiveColType) {
        throw new IOException("Hive does not support the SQL type for column " + col);
      }

      sb.append(col + " " + hiveColType);

      if (HiveTypes.isHiveTypeImprovised(colType)) {
        LOG.warn("Column " + col + " had to be cast to a less precise type in Hive");
      }
    }

    sb.append(") ");

    if (commentsEnabled) {
      DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
      String curDateStr = dateFormat.format(new Date());
      sb.append("COMMENT 'Imported by sqoop on " + curDateStr + "' ");
    }

    sb.append("ROW FORMAT DELIMITED FIELDS TERMINATED BY '");
    sb.append(getHiveOctalCharCode((int) options.getOutputFieldDelim()));
    sb.append("' LINES TERMINATED BY '");
    sb.append(getHiveOctalCharCode((int) options.getOutputRecordDelim()));
    sb.append("' STORED AS TEXTFILE");

    LOG.debug("Create statement: " + sb.toString());
    return sb.toString();
  }

  private static final int DEFAULT_HDFS_PORT =
      org.apache.hadoop.hdfs.server.namenode.NameNode.DEFAULT_PORT;

  /**
   * @return the LOAD DATA statement to import the data in HDFS into hive
   */
  public String getLoadDataStmt() throws IOException { 
    String warehouseDir = options.getWarehouseDir();
    if (null == warehouseDir) {
      warehouseDir = "";
    } else if (!warehouseDir.endsWith(File.separator)) {
      warehouseDir = warehouseDir + File.separator;
    }

    String tablePath = warehouseDir + inputTableName;
    FileSystem fs = FileSystem.get(configuration);
    Path finalPath = new Path(tablePath).makeQualified(fs);
    String finalPathStr = finalPath.toString();

    StringBuilder sb = new StringBuilder();
    sb.append("LOAD DATA INPATH '");
    sb.append(finalPathStr);
    sb.append("' INTO TABLE ");
    sb.append(outputTableName);

    LOG.debug("Load statement: " + sb.toString());
    return sb.toString();
  }

  /**
   * Return a string identifying the character to use as a delimiter
   * in Hive, in octal representation.
   * Hive can specify delimiter characters in the form '\ooo' where
   * ooo is a three-digit octal number between 000 and 177. Values
   * may not be truncated ('\12' is wrong; '\012' is ok) nor may they
   * be zero-prefixed (e.g., '\0177' is wrong).
   *
   * @param charNum the character to use as a delimiter
   * @return a string of the form "\ooo" where ooo is an octal number
   * in [000, 177].
   * @throws IllegalArgumentException if charNum &gt;> 0177.
   */
  static String getHiveOctalCharCode(int charNum)
      throws IllegalArgumentException {
    if (charNum > 0177) {
      throw new IllegalArgumentException(
          "Character " + charNum + " is an out-of-range delimiter");
    }

    return String.format("\\%03o", charNum);
  }
}

