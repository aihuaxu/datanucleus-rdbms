/**********************************************************************
Copyright (c) 2008 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.sql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.store.rdbms.identifier.DatastoreIdentifier;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.rdbms.RDBMSPropertyNames;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.adapter.DatastoreAdapter;
import org.datanucleus.store.rdbms.query.QueryGenerator;
import org.datanucleus.store.rdbms.sql.SQLJoin.JoinType;
import org.datanucleus.store.rdbms.sql.expression.BooleanExpression;
import org.datanucleus.store.rdbms.sql.expression.BooleanLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpressionFactory;
import org.datanucleus.store.rdbms.table.Table;
import org.datanucleus.store.schema.table.SurrogateColumnType;
import org.datanucleus.util.NucleusLogger;

/**
 * Class providing an API for generating SQL statements.
 * Caller should create the SQLStatement object and (optionally) call setClassLoaderResolver() to set any class loading restriction.
 * Then the caller builds up the statement using the various methods, and accesses the SQL statement using getSQLText(). 
 * <p>
 * The generated SQL is cached. Any use of a mutating method, changing the composition of the statement
 * will clear the cached SQL, and it will be regenerated when <pre>getSQLText</pre> is called next.
 * 
 * <h3>Table Groups</h3>
 * When tables are registered in the statement they are split into "table groups". A table group is,
 * in simple terms, an object in the query. If a table has a super-table and a field of the object
 * is selected that is in the super-table then the super-table is added to the table group. If there
 * is a join to a related object then the table of this object will be put in a new table group.
 * So the same datastore table can appear multiple times in the statement, each time for a different
 * object.
 * 
 * <h3>Table Aliases</h3>
 * All methods that cause a new SQLTable to be created also allow specification of the table alias
 * in the statement. Where the alias is not provided then we use a table "namer" (definable on the
 * plugin-point "org.datanucleus.store.rdbms.sql_tablenamer"). The table namer can define names
 * simply based on the table number, or based on table group and the number of tables in the group
 * etc etc. 
 * To select a particular table "namer", set the extension "table-naming-strategy" to the key of the namer plugin. 
 * The default is "alpha-scheme" which bases table names on the group and number in that group.
 * 
 * <b>Note that this class is not intended to be thread-safe. It is used by a single ExecutionContext</b>
 */
public abstract class SQLStatement
{
    public static final String EXTENSION_SQL_TABLE_NAMING_STRATEGY = "table-naming-strategy";
    public static final String EXTENSION_LOCK_FOR_UPDATE = "lock-for-update";
    public static final String EXTENSION_LOCK_FOR_UPDATE_NOWAIT = "for-update-nowait";

    /** Map of SQLTable naming instance keyed by the name of the naming scheme. */
    protected static final Map<String, SQLTableNamer> tableNamerByName = new ConcurrentHashMap<>();

    /** Cached SQL statement, generated by getSQLText(). */
    protected SQLText sql = null;

    /** Manager for the RDBMS datastore. */
    protected RDBMSStoreManager rdbmsMgr;

    /** ClassLoader resolver to use. Used by sub-expressions. Defaults to the loader resolver for the store manager. */
    protected ClassLoaderResolver clr;

    /** Context of any query generation. */
    protected QueryGenerator queryGenerator = null;

    protected SQLTableNamer namer = null;

    /** Name of class that this statement selects (optional, only typically for unioned statements). */
    protected String candidateClassName = null;

    /** Map of extensions for use in generating the SQL, keyed by the extension name. */
    protected Map<String, Object> extensions;

    /** Parent statement, if this is a subquery SELECT. Must be set at construction. */
    protected SQLStatement parent = null;

    /** Primary table for this statement. */
    protected SQLTable primaryTable;

    /** List of joins for this statement. */
    protected List<SQLJoin> joins;

    protected boolean requiresJoinReorder = false;

    /** Map of tables referenced in this statement, keyed by their alias. Note that these aliases are in the input case. */
    protected Map<String, SQLTable> tables;

    /** Map of table groups keyed by the group name. */
    protected Map<String, SQLTableGroup> tableGroups = new HashMap<>();

    /** Where clause. */
    protected BooleanExpression where;

    /**
     * Constructor for an SQL statement that is a subquery of another statement.
     * @param parentStmt Parent statement
     * @param rdbmsMgr The datastore manager
     * @param table The primary table
     * @param alias Alias for this table
     * @param tableGroupName Name of candidate table-group (if any). Uses "Group0" if not provided
     * @param extensions Any extensions (optional)
     */
    public SQLStatement(SQLStatement parentStmt, RDBMSStoreManager rdbmsMgr, Table table, DatastoreIdentifier alias, String tableGroupName, Map<String, Object> extensions)
    {
        this.parent = parentStmt;
        this.rdbmsMgr = rdbmsMgr;

        // Set the namer, using any override extension, otherwise the RDBMS default
        String namingStrategy = rdbmsMgr.getStringProperty(RDBMSPropertyNames.PROPERTY_RDBMS_SQL_TABLE_NAMING_STRATEGY);
        if (extensions != null && extensions.containsKey(EXTENSION_SQL_TABLE_NAMING_STRATEGY))
        {
            namingStrategy = (String) extensions.get(EXTENSION_SQL_TABLE_NAMING_STRATEGY);
        }
        namer = getTableNamer(namingStrategy);

        String tableGrpName = (tableGroupName != null ? tableGroupName : "Group0");
        if (alias == null)
        {
            // No alias provided so generate one
            alias = rdbmsMgr.getIdentifierFactory().newTableIdentifier(namer.getAliasForTable(this, table, tableGrpName));
        }
        this.primaryTable = new SQLTable(this, table, alias, tableGrpName);
        putSQLTableInGroup(primaryTable, tableGrpName, null);

        if (parentStmt != null)
        {
            // Use same query generator
            queryGenerator = parentStmt.getQueryGenerator();
        }
    }

    public RDBMSStoreManager getRDBMSManager()
    {
        return rdbmsMgr;
    }

    public void setClassLoaderResolver(ClassLoaderResolver clr)
    {
        this.clr = clr;
    }

    public ClassLoaderResolver getClassLoaderResolver()
    {
        if (clr == null)
        {
            clr = rdbmsMgr.getNucleusContext().getClassLoaderResolver(null);
        }
        return clr;
    }

    public void setCandidateClassName(String name)
    {
        this.candidateClassName = name;
    }

    public String getCandidateClassName()
    {
        return candidateClassName;
    }

    public QueryGenerator getQueryGenerator()
    {
        return queryGenerator;
    }

    public void setQueryGenerator(QueryGenerator gen)
    {
        this.queryGenerator = gen;
    }

    public SQLExpressionFactory getSQLExpressionFactory()
    {
        return rdbmsMgr.getSQLExpressionFactory();
    }

    public DatastoreAdapter getDatastoreAdapter()
    {
        return rdbmsMgr.getDatastoreAdapter();
    }

    public SQLStatement getParentStatement()
    {
        return parent;
    }

    /**
     * Convenience method to return if this statement is a child (inner) statement of the supplied
     * statement.
     * @param stmt The statement that may be parent, grandparent etc of this statement
     * @return Whether this is a child of the supplied statement
     */
    public boolean isChildStatementOf(SQLStatement stmt)
    {
        if (stmt == null || parent == null)
        {
            return false;
        }

        if (stmt == parent)
        {
            return true;
        }
        return isChildStatementOf(parent);
    }

    /**
     * Method to define an extension for this query statement allowing control over its behaviour in generating a query.
     * @param key Extension key
     * @param value Value for the key
     */
    public void addExtension(String key, Object value)
    {
        if (key == null)
        {
            return;
        }
        invalidateStatement();

        if (key.equals(EXTENSION_SQL_TABLE_NAMING_STRATEGY))
        {
            namer = getTableNamer((String) value);
            return;
        }

        if (extensions == null)
        {
            extensions = new HashMap();
        }
        extensions.put(key, value);
    }

    /**
     * Accessor for the value for an extension.
     * @param key Key for the extension
     * @return Value for the extension (if any)
     */
    public Object getValueForExtension(String key)
    {
        if (extensions == null)
        {
            return extensions;
        }
        return extensions.get(key);
    }

    // --------------------------------- FROM --------------------------------------

    /**
     * Accessor for the primary table of the statement.
     * @return The primary table
     */
    public SQLTable getPrimaryTable()
    {
        return primaryTable;
    }

    /**
     * Accessor for the SQLTable object with the specified alias (if defined for this statement).
     * Note that this alias should be in the same case as what they were defined to the statement as.
     * @param alias Alias
     * @return The SQLTable
     */
    public SQLTable getTable(String alias)
    {
        if (alias.equals(primaryTable.alias.getName()))
        {
            return primaryTable;
        }
        else if (tables != null)
        {
            return tables.get(alias);
        }
        return null;
    }

    /**
     * Convenience method to find a registered SQLTable that is for the specified table
     * @param table The table
     * @return The SQLTable (or null if not referenced)
     */
    public SQLTable getTableForDatastoreContainer(Table table)
    {
        for (SQLTableGroup grp : tableGroups.values())
        {
            SQLTable[] tbls = grp.getTables();
            for (SQLTable tbl : tbls)
            {
                if (tbl.getTable() == table)
                {
                    return tbl;
                }
            }
        }
        return null;
    }

    /**
     * Accessor for the SQLTable object for the specified table (if defined for this statement)
     * in the specified table group.
     * @param table The table
     * @param groupName Name of the table group where we should look for this table
     * @return The SQLTable (if found)
     */
    public SQLTable getTable(Table table, String groupName)
    {
        if (groupName == null)
        {
            return null;
        }

        SQLTableGroup tableGrp = tableGroups.get(groupName);
        if (tableGrp == null)
        {
            return null;
        }
        SQLTable[] sqlTbls = tableGrp.getTables();
        for (SQLTable sqlTbl : sqlTbls)
        {
            if (sqlTbl.getTable() == table)
            {
                return sqlTbl;
            }
        }
        return null;
    }

    /**
     * Accessor for the table group with this name.
     * @param groupName Name of the group
     * @return The table group
     */
    public SQLTableGroup getTableGroup(String groupName)
    {
        return tableGroups.get(groupName);
    }

    /**
     * Accessor for the number of table groups.
     * @return Number of table groups (including that of the candidate)
     */
    public int getNumberOfTableGroups()
    {
        return tableGroups.size();
    }

    /**
     * Accessor for the number of tables defined for this statement.
     * @return Number of tables (in addition to the primary table)
     */
    public int getNumberOfTables()
    {
        return tables != null ? tables.size() : -1;
    }

    /**
     * Method to form a join to the specified table using the provided mappings, with the join also being applied to any UNIONed statements.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping,
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName)
    {
        return join(joinType, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, true, null);
    }

    /**
     * Method to form a join to the specified table using the provided mappings, with the join also being applied to any UNIONed statements.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @param parentJoin Parent join when this join will be a sub-join (part of "join grouping")
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping,
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName, SQLJoin parentJoin)
    {
        return join(joinType, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, true, parentJoin);
    }

    /**
     * Method to form a join to the specified table using the provided mappings.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @param applyToUnions Whether to apply to any unioned statements (only applies to SELECT statements)
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping, 
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName, boolean applyToUnions)
    {
        return join(joinType, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, applyToUnions, null);
    }

    /**
     * Method to form a join to the specified table using the provided mappings.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @param applyToUnions Whether to apply to any unioned statements (only applies to SELECT statements)
     * @param parentJoin Parent join when this join will be a sub-join (part of "join grouping")
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping, 
            Table target, String targetAlias, JavaTypeMapping targetMapping, Object[] discrimValues, String tableGrpName, boolean applyToUnions, SQLJoin parentJoin)
    {
        return join(joinType, sourceTable, sourceMapping, null, target, targetAlias, targetMapping, null, discrimValues, tableGrpName, applyToUnions, parentJoin);
    }

    /**
     * Method to form a join to the specified table using the provided mappings, with the join condition derived from the source-target mappings.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param sourceMapping Mapping in this table to join from
     * @param sourceParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param targetMapping Mapping in the other table to join to (also defines the table to join to)
     * @param targetParentMapping Optional, if this source mapping is a sub mapping (e.g interface impl).
     * @param discrimValues Any discriminator values to apply for the joined table (null if not)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @param applyToUnions Whether to apply to any unioned statements (only applies to SELECT statements)
     * @param parentJoin Parent join when this join will be a sub-join (part of "join grouping")
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            Table target, String targetAlias, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping, Object[] discrimValues, String tableGrpName, boolean applyToUnions,
            SQLJoin parentJoin)
    {
        invalidateStatement();

        // Create the SQLTable to join to.
        if (tables == null)
        {
            tables = new HashMap();
        }
        if (tableGrpName == null)
        {
            tableGrpName = "Group" + tableGroups.size();
        }
        if (targetAlias == null)
        {
            targetAlias = namer.getAliasForTable(this, target, tableGrpName);
        }
        if (sourceTable == null)
        {
            sourceTable = primaryTable;
        }
        DatastoreIdentifier targetId = rdbmsMgr.getIdentifierFactory().newTableIdentifier(targetAlias);
        SQLTable targetTbl = new SQLTable(this, target, targetId, tableGrpName);
        putSQLTableInGroup(targetTbl, tableGrpName, joinType);

        // Generate the join condition to use
        BooleanExpression joinCondition = getJoinConditionForJoin(sourceTable, sourceMapping, sourceParentMapping, targetTbl, targetMapping, targetParentMapping, discrimValues);

        addJoin(joinType, sourceTable, targetTbl, joinCondition, parentJoin);

        return targetTbl;
    }

    /**
     * Method to form a join to the specified table using the provided mappings and applying the provided join condition (rather than generating one from the source/target mappings).
     * This is used with JPQL where we allow two root entities to be joined using a provide "ON" condition.
     * @param joinType Type of join.
     * @param sourceTable SQLTable for the source (null implies primaryTable)
     * @param target Table to join to
     * @param targetAlias Alias for the target table (if known)
     * @param tableGrpName Name of the table group for the target (null implies a new group)
     * @param joinCondition On clause for the join
     * @param applyToUnions Whether to apply to any unioned statements (only applies to SELECT statements)
     * @return SQLTable for the target
     */
    public SQLTable join(JoinType joinType, SQLTable sourceTable, Table target, String targetAlias, String tableGrpName, BooleanExpression joinCondition, boolean applyToUnions)
    {
        invalidateStatement();

        // Create the SQLTable to join to.
        if (tables == null)
        {
            tables = new HashMap();
        }
        if (tableGrpName == null)
        {
            tableGrpName = "Group" + tableGroups.size();
        }
        if (targetAlias == null)
        {
            targetAlias = namer.getAliasForTable(this, target, tableGrpName);
        }
        if (sourceTable == null)
        {
            sourceTable = primaryTable;
        }
        DatastoreIdentifier targetId = rdbmsMgr.getIdentifierFactory().newTableIdentifier(targetAlias);
        SQLTable targetTbl = new SQLTable(this, target, targetId, tableGrpName);
        putSQLTableInGroup(targetTbl, tableGrpName, joinType);

        addJoin(joinType, sourceTable, targetTbl, joinCondition, null);

        return targetTbl;
    }

    /**
     * Accessor for the type of join used for the specified table.
     * @param sqlTbl The table to check
     * @return The join type, or null if not joined in this statement
     */
    public JoinType getJoinTypeForTable(SQLTable sqlTbl)
    {
        if (joins == null)
        {
            return null;
        }
        Iterator<SQLJoin> joinIter = joins.iterator();
        while (joinIter.hasNext())
        {
            SQLJoin join = joinIter.next();
            if (join.getTargetTable().equals(sqlTbl))
            {
                return join.getType();
            }
            if (join.getSubJoin() != null)
            {
                if (join.getSubJoin().getTargetTable().equals(sqlTbl))
                {
                    return join.getSubJoin().getType();
                }
            }
        }
        return null;
    }

    /**
     * Method to find the JOIN for the specified table and add the specified 'and' condition to the JOIN as an 'ON' clause.
     * @param sqlTbl The table
     * @param andCondition The 'ON' condition to add
     * @param applyToUnions Whether to apply to unions (see SelectStatement)
     */
    public void addAndConditionToJoinForTable(SQLTable sqlTbl, BooleanExpression andCondition, boolean applyToUnions)
    {
        SQLJoin join = getJoinForTable(sqlTbl);
        if (join != null)
        {
            join.addAndCondition(andCondition);
        }
    }

    /**
     * Accessor for the type of join used for the specified table.
     * @param sqlTbl The table to check
     * @return The join type, or null if not joined in this statement
     */
    public SQLJoin getJoinForTable(SQLTable sqlTbl)
    {
        if (joins == null)
        {
            return null;
        }
        Iterator<SQLJoin> joinIter = joins.iterator();
        while (joinIter.hasNext())
        {
            SQLJoin join = joinIter.next();
            if (join.getTargetTable().equals(sqlTbl))
            {
                return join;
            }
            if (join.getSubJoin() != null)
            {
                if (join.getSubJoin().getTargetTable().equals(sqlTbl))
                {
                    return join.getSubJoin();
                }
            }
        }
        return null;
    }

    /**
     * Method to remove a cross join for the specified table (if joined via cross join).
     * Also removes the table from the list of tables.
     * This is called where we have bound a variable via a CROSS JOIN (in the absence of better information)
     * and found out later it could become an INNER JOIN.
     * If the supplied table is not joined via a cross join then does nothing.
     * @param targetSqlTbl The table to drop the cross join for
     * @return The removed alias
     */
    public String removeCrossJoin(SQLTable targetSqlTbl)
    {
        if (joins == null)
        {
            return null;
        }

        Iterator<SQLJoin> joinIter = joins.iterator();
        while (joinIter.hasNext())
        {
            SQLJoin join = joinIter.next();
            if (join.getTargetTable().equals(targetSqlTbl) && join.getType() == JoinType.CROSS_JOIN)
            {
                joinIter.remove();
                requiresJoinReorder = true;
                tables.remove(join.getTargetTable().alias.getName());
                String removedAliasName = join.getTargetTable().alias.getName();

                return removedAliasName;
            }
        }

        return null;
    }

    /**
     * Convenience method to add the SQLTable to the specified group.
     * If the group doesn't yet exist then it adds it.
     * @param sqlTbl SQLTable to add
     * @param groupName The group
     * @param joinType type of join to start this table group
     */
    protected void putSQLTableInGroup(SQLTable sqlTbl, String groupName, JoinType joinType)
    {
        SQLTableGroup tableGrp = tableGroups.get(groupName);
        if (tableGrp == null)
        {
            tableGrp = new SQLTableGroup(groupName, joinType);
        }
        tableGrp.addTable(sqlTbl);
        tableGroups.put(groupName, tableGrp);
    }

    /**
     * Internal method to form a join to the specified table using the provided mappings.
     * @param joinType Type of join (INNER, LEFT OUTER, RIGHT OUTER, CROSS, NON-ANSI)
     * @param sourceTable SQLTable to join from
     * @param targetTable SQLTable to join to
     * @param joinCondition Condition for the join
     * @param parentJoin Optional parent join (which will mean this join becomes a sub-join)
     */
    protected void addJoin(SQLJoin.JoinType joinType, SQLTable sourceTable, SQLTable targetTable, BooleanExpression joinCondition, SQLJoin parentJoin)
    {
        if (tables == null)
        {
            throw new NucleusException("tables not set in statement!");
        }
        if (tables.containsValue(targetTable))
        {
            // Already have a join to this table
            // What if we have a cross join, and want to change to inner join?
            NucleusLogger.DATASTORE.debug("Attempt to join to " + targetTable + " but join already exists");
            return;
        }
        if (joinType == JoinType.RIGHT_OUTER_JOIN && !rdbmsMgr.getDatastoreAdapter().supportsOption(DatastoreAdapter.RIGHT_OUTER_JOIN))
        {
            throw new NucleusUserException("RIGHT OUTER JOIN is not supported by this datastore");
        }
        if (parentJoin != null && parentJoin.getSubJoin() != null)
        {
            throw new NucleusException("Attempt to create sub-join for " + parentJoin + " but already has a sub-join");
        }

        // Add the table to the referenced tables for this statement
        tables.put(targetTable.alias.getName(), targetTable);

        if (joins == null)
        {
            joins = new ArrayList<>();
        }

        if (rdbmsMgr.getDatastoreAdapter().supportsOption(DatastoreAdapter.ANSI_JOIN_SYNTAX))
        {
            // "ANSI-92" style join
            SQLJoin join = new SQLJoin(joinType, targetTable, sourceTable, joinCondition);

            int position = -1;
            if (queryGenerator != null && queryGenerator.processingOnClause())
            {
                // We are processing an ON condition, and this JOIN has been forced, so position it dependent on what it joins from
                if (primaryTable == sourceTable)
                {
                    if (joins.size() > 0)
                    {
                        position = 0;
                    }
                }
                else
                {
                    int i=1;
                    for (SQLJoin sqlJoin : joins)
                    {
                        if (sqlJoin.getSourceTable() == sourceTable)
                        {
                            position = i;
                            break;
                        }
                        else if (sqlJoin.getSubJoin() != null && sqlJoin.getSubJoin().getSourceTable() == sourceTable)
                        {
                            position = i;
                            break;
                        }
                        i++;
                    }
                }
            }

            if (parentJoin == null)
            {
                if (position >= 0)
                {
                    joins.add(position, join);
                }
                else
                {
                    joins.add(join);
                }
            }
            else
            {
                parentJoin.setSubJoin(join);
            }
        }
        else
        {
            // "ANSI-86" style join
            SQLJoin join = new SQLJoin(JoinType.NON_ANSI_JOIN, targetTable, sourceTable, null);
            joins.add(join);

            // Specify joinCondition in the WHERE clause since not allowed in FROM clause with ANSI-86
            // TODO Cater for Oracle LEFT OUTER syntax "(+)"
            whereAnd(joinCondition, false);

            // TODO Support JOIN groups for non-ANSI joins?
        }
    }

    /**
     * Convenience method to generate the join condition between source and target tables for the supplied mappings.
     * @param sourceTable Source table
     * @param sourceMapping Mapping in source table
     * @param sourceParentMapping Optional parent of this source mapping (if joining an impl of an interface)
     * @param targetTable Target table
     * @param targetMapping Mapping in target table
     * @param targetParentMapping Optional parent of this target mapping (if joining an impl of an interface)
     * @param discrimValues Optional discriminator values to further restrict
     * @return The join condition
     */
    protected BooleanExpression getJoinConditionForJoin(
            SQLTable sourceTable, JavaTypeMapping sourceMapping, JavaTypeMapping sourceParentMapping,
            SQLTable targetTable, JavaTypeMapping targetMapping, JavaTypeMapping targetParentMapping,
            Object[] discrimValues)
    {
        BooleanExpression joinCondition = null;
        if (sourceMapping != null && targetMapping != null)
        {
            // Join condition(s) - INNER, LEFT OUTER, RIGHT OUTER joins
            if (sourceMapping.getNumberOfDatastoreMappings() != targetMapping.getNumberOfDatastoreMappings())
            {
                throw new NucleusException("Cannot join from " + sourceMapping + " to " + targetMapping + " since they have different numbers of datastore columns!");
            }

            SQLExpressionFactory factory = rdbmsMgr.getSQLExpressionFactory();

            // Set joinCondition to be "source = target"
            SQLExpression sourceExpr = null;
            if (sourceParentMapping == null)
            {
                sourceExpr = factory.newExpression(this, sourceTable != null ? sourceTable : primaryTable, sourceMapping);
            }
            else
            {
                sourceExpr = factory.newExpression(this, sourceTable != null ? sourceTable : primaryTable, sourceMapping, sourceParentMapping);
            }

            SQLExpression targetExpr = null;
            if (targetParentMapping == null)
            {
                targetExpr = factory.newExpression(this, targetTable, targetMapping);
            }
            else
            {
                targetExpr = factory.newExpression(this, targetTable, targetMapping, targetParentMapping);
            }

            joinCondition = sourceExpr.eq(targetExpr);

            // Process discriminator for any additional conditions
            JavaTypeMapping discrimMapping = targetTable.getTable().getSurrogateMapping(SurrogateColumnType.DISCRIMINATOR, false);
            if (discrimMapping != null && discrimValues != null)
            {
                SQLExpression discrimExpr = factory.newExpression(this, targetTable, discrimMapping);
                BooleanExpression discrimCondition = null;
                for (Object discrimValue : discrimValues)
                {
                    SQLExpression discrimVal = factory.newLiteral(this, discrimMapping, discrimValue);
                    BooleanExpression condition = discrimExpr.eq(discrimVal);
                    if (discrimCondition == null)
                    {
                        discrimCondition = condition;
                    }
                    else
                    {
                        discrimCondition = discrimCondition.ior(condition);
                    }
                }
                if (discrimCondition != null)
                {
                    discrimCondition.encloseInParentheses();
                    joinCondition = joinCondition.and(discrimCondition);
                }
            }
        }
        return joinCondition;
    }

    /**
     * Method to return the namer for a particular schema.
     * If there is no instantiated namer for this schema then instantiates one.
     * @param namingSchema Table naming schema to use
     * @return The namer
     */
    protected synchronized SQLTableNamer getTableNamer(String namingSchema)
    {
        SQLTableNamer namer = tableNamerByName.get(namingSchema);
        if (namer == null)
        {
            // Instantiate the namer of this schema name (if available)
            if (namingSchema.equalsIgnoreCase("t-scheme"))
            {
                namer = new SQLTableTNamer();
            }
            else if (namingSchema.equalsIgnoreCase("alpha-scheme"))
            {
                namer = new SQLTableAlphaNamer();
            }
            else if (namingSchema.equalsIgnoreCase("table-name"))
            {
                namer = new SQLTableNameNamer();
            }
            else
            {
                // Fallback to the plugin mechanism
                try
                {
                    namer = (SQLTableNamer)rdbmsMgr.getNucleusContext().getPluginManager().createExecutableExtension(
                        "org.datanucleus.store.rdbms.sql_tablenamer", "name", namingSchema, "class", null, null);
                }
                catch (Exception e)
                {
                    throw new NucleusException("Attempt to find/instantiate SQL table namer " + namingSchema + " threw an exception", e);
                }
            }

            tableNamerByName.put(namingSchema, namer);
        }
        return namer;
    }

    // --------------------------------- WHERE --------------------------------------

    /**
     * Method to add an AND condition to the WHERE clause.
     * @param expr The condition
     * @param applyToUnions whether to apply this and to any UNIONs in the statement (only applies to SELECT statements)
     */
    public void whereAnd(BooleanExpression expr, boolean applyToUnions)
    {
        if (expr instanceof BooleanLiteral && !expr.isParameter() && (Boolean)((BooleanLiteral)expr).getValue())
        {
            // Where condition is "TRUE" so omit
            return;
        }

        invalidateStatement();

        if (where == null)
        {
            where = expr;
        }
        else
        {
            where = where.and(expr);
        }
    }

    /**
     * Method to add an OR condition to the WHERE clause.
     * @param expr The condition
     * @param applyToUnions Whether to apply to unions (only applies to SELECT statements)
     */
    public void whereOr(BooleanExpression expr, boolean applyToUnions)
    {
        invalidateStatement();

        if (where == null)
        {
            where = expr;
        }
        else
        {
            where = where.ior(expr);
        }
    }

    public synchronized SQLText getSQLText()
    {
        return sql;
    }

    /**
     * Method to uncache the generated SQL (because some condition has changed).
     */
    protected void invalidateStatement()
    {
        sql = null;
    }

    /**
     * Method to dump the statement to the supplied log (debug level).
     * Logs the SQL that this statement equates to, and the TableGroup(s) and their associated tables.
     * @param logger The logger
     */
    public void log(NucleusLogger logger)
    {
        // Log the statement
        logger.debug("SQLStatement : " + getSQLText());

        // Log the table groups
        Iterator grpIter = tableGroups.keySet().iterator();
        while (grpIter.hasNext())
        {
            String grpName = (String)grpIter.next();
            logger.debug("SQLStatement : TableGroup=" + tableGroups.get(grpName));
        }
    }
}