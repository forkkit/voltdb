/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.plannodes;

import java.util.*;

import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONStringer;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Database;
import org.voltdb.compiler.DatabaseEstimates;
import org.voltdb.compiler.ScalarValueHints;
import org.voltdb.expressions.*;
import org.voltdb.planner.PlanStatistics;
import org.voltdb.types.*;

public class ProjectionPlanNode extends AbstractPlanNode {

    public ProjectionPlanNode() {
        super();
    }

    @Override
    public PlanNodeType getPlanNodeType() {
        return PlanNodeType.PROJECTION;
    }

    @Override
    public void validate() throws Exception {
        super.validate();

        // Validate Expression Trees
        for (int ctr = 0; ctr < m_outputSchema.getColumns().size(); ctr++) {
            SchemaColumn column = m_outputSchema.getColumns().get(ctr);
            AbstractExpression exp = column.getExpression();
            if (exp == null) {
                throw new Exception("ERROR: The Output Column Expression at position '" + ctr + "' is NULL");
            }
            exp.validate();
        }
    }

    /**
     * Set the output schema for this projection.  This schema will be
     * treated as immutable during the planning (aside from resolving
     * column indexes for TVEs within any expressions in these columns)
     * @param schema
     */
    public void setOutputSchema(NodeSchema schema)
    {
        m_outputSchema = schema.clone();
    }

    @Override
    public void resolveColumnIndexes()
    {
        assert(m_children.size() == 1);
        m_children.get(0).resolveColumnIndexes();
        NodeSchema input_schema = m_children.get(0).getOutputSchema();
        resolveColumnIndexesUsingSchema(input_schema);
    }

    /**
     * Given an input schema, resolve all the TVEs in all the output column
     * expressions.  This method is necessary to be able to do this for
     * inlined projection nodes that don't have a child from which they can get
     * an output schema.
     */
    void resolveColumnIndexesUsingSchema(NodeSchema inputSchema)
    {
        // get all the TVEs in the output columns
        List<TupleValueExpression> output_tves =
            new ArrayList<TupleValueExpression>();
        for (SchemaColumn col : m_outputSchema.getColumns())
        {
            output_tves.addAll(ExpressionUtil.getTupleValueExpressions(col.getExpression()));
        }
        // and update their indexes against the table schema
        for (TupleValueExpression tve : output_tves)
        {
            int index = inputSchema.getIndexOfTve(tve);
            tve.setColumnIndex(index);
        }
        // DON'T RE-SORT HERE
    }

    @Override
    public void generateOutputSchema(Database db)
    {
        assert(m_children.size() == 1);
        m_children.get(0).generateOutputSchema(db);
        // SCARY MAGIC
        // projection's output schema is mostly pre-determined, however,
        // since aggregates are generated by an earlier node, we need
        // to replace any aggregate expressions in the display columns
        // with a tuple value expression that matches what we're going
        // to generate out of the aggregate node
        NodeSchema new_schema = new NodeSchema();
        for (SchemaColumn col : m_outputSchema.getColumns())
        {
            if (col.getExpression().getExpressionType() == ExpressionType.AGGREGATE_SUM ||
                col.getExpression().getExpressionType() == ExpressionType.AGGREGATE_COUNT ||
                col.getExpression().getExpressionType() == ExpressionType.AGGREGATE_COUNT_STAR ||
                col.getExpression().getExpressionType() == ExpressionType.AGGREGATE_MIN ||
                col.getExpression().getExpressionType() == ExpressionType.AGGREGATE_MAX ||
                col.getExpression().getExpressionType() == ExpressionType.AGGREGATE_AVG)
            {
                NodeSchema input_schema = m_children.get(0).getOutputSchema();
                SchemaColumn agg_col = input_schema.find(col.getTableName(),
                                                         col.getColumnName(),
                                                         col.getColumnAlias());
                if (agg_col == null)
                {
                    throw new RuntimeException("Unable to find matching " +
                                               "input column for projection: " +
                                               col.toString());
                }
                new_schema.addColumn(col.copyAndReplaceWithTVE());
            }
            else
            {
                new_schema.addColumn(col.clone());
            }
        }
        m_outputSchema = new_schema;

        return;
    }

    @Override
    public boolean computeEstimatesRecursively(PlanStatistics stats,
            Cluster cluster, Database db, DatabaseEstimates estimates, ScalarValueHints[] paramHints) {
        // TODO Auto-generated method stub
        return super.computeEstimatesRecursively(stats, cluster, db, estimates, paramHints);
    }

    @Override
    public void toJSONString(JSONStringer stringer) throws JSONException {
        super.toJSONString(stringer);
    }
}
