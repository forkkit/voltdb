/* This file is part of VoltDB.
 * Copyright (C) 2008-2020 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.dtxn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;

import org.voltdb.ClientInterface;
import org.voltdb.SiteStatsSource;
import org.voltdb.StatsSelector;
import org.voltdb.VoltDB;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.client.ClientResponse;

/**
 * Class that provides storage for statistical information generated by an Initiator
 */
// NOTE: Initiator stats aren't per-site (which is the point of the SiteStatsSource)
// but changing this now affects a public API, so, we'll just fill it in with the host ID,
// print the HOST ID twice in the result table, and move on.
public class InitiatorStats extends SiteStatsSource {

    public enum Initiator {
        CONNECTION_ID               (VoltType.BIGINT),
        CONNECTION_HOSTNAME         (VoltType.STRING),
        PROCEDURE_NAME              (VoltType.STRING),
        INVOCATIONS                 (VoltType.BIGINT),
        AVG_EXECUTION_TIME          (VoltType.INTEGER),
        MIN_EXECUTION_TIME          (VoltType.INTEGER),
        MAX_EXECUTION_TIME          (VoltType.INTEGER),
        ABORTS                      (VoltType.BIGINT),
        FAILURES                    (VoltType.BIGINT);

        public final VoltType m_type;
        Initiator(VoltType type) { m_type = type; }
    }

    /**
     *
     * @param name
     * @param siteId
     */
    public InitiatorStats(long hostId) {
        super(hostId, false);
        VoltDB.instance().getStatsAgent().registerStatsSource(StatsSelector.INITIATOR, 0, this);
    }

    public static class InvocationInfo {

        /**
         * Hostname of the host this connection is with
         */
        private final String connectionHostname;

        /**
         * Number of time procedure has been invoked
         */
        private long invocationCount = 0;
        private long lastInvocationCount = 0;

        /**
         * Shortest amount of time this procedure has executed in
         */
        private int minExecutionTime = Integer.MAX_VALUE;
        private int lastMinExecutionTime = Integer.MAX_VALUE;

        /**
         * Longest amount of time this procedure has executed in
         */
        private int maxExecutionTime = Integer.MIN_VALUE;
        private int lastMaxExecutionTime = Integer.MIN_VALUE;

        /**
         * Total amount of time spent executing procedure
         */
        private long totalExecutionTime = 0;
        private long lastTotalExecutionTime = 0;

        private long abortCount = 0;
        private long lastAbortCount = 0;
        private long failureCount = 0;
        private long lastFailureCount = 0;

        public InvocationInfo (String hostname) {
            connectionHostname = hostname;
        }

        public void processInvocation(int delta, byte status) {
            totalExecutionTime += delta;
            minExecutionTime = Math.min( delta, minExecutionTime);
            maxExecutionTime = Math.max(  delta, maxExecutionTime);
            lastMinExecutionTime = Math.min( delta, lastMinExecutionTime);
            lastMaxExecutionTime = Math.max( delta, lastMaxExecutionTime);
            invocationCount++;
            switch (status) {
            case ClientResponse.SUCCESS:
                break;
            case ClientResponse.USER_ABORT:
            case ClientResponse.GRACEFUL_FAILURE:
            case ClientResponse.UNSUPPORTED_DYNAMIC_CHANGE:
                abortCount++;
                break;
            default:
                failureCount++;
                break;
            }
        }
    }

    @Override
    protected void populateColumnSchema(ArrayList<ColumnInfo> columns) {
        super.populateColumnSchema(columns, Initiator.class);
    }

    @Override
    protected int updateStatsRow(final Object rowKey, Object rowValues[]) {
        int offset = super.updateStatsRow(rowKey, rowValues);
        DummyIterator iterator = (DummyIterator)rowKey;
        Map.Entry<String, InvocationInfo> entry = iterator.innerNext;
        iterator.innerNext = null;
        final InvocationInfo info = entry.getValue();
        final String procName = entry.getKey();
        final Long connectionId = iterator.outerNext.getKey();

        long invocationCount = info.invocationCount;
        long totalExecutionTime = info.totalExecutionTime;
        int minExecutionTime = info.minExecutionTime;
        int maxExecutionTime = info.maxExecutionTime;
        long abortCount = info.abortCount;
        long failureCount = info.failureCount;

        if (iterator.interval) {
            invocationCount = info.invocationCount - info.lastInvocationCount;
            info.lastInvocationCount = info.invocationCount;

            totalExecutionTime = info.totalExecutionTime - info.lastTotalExecutionTime;
            info.lastTotalExecutionTime = info.totalExecutionTime;

            minExecutionTime = info.lastMinExecutionTime;
            maxExecutionTime = info.lastMaxExecutionTime;
            info.lastMinExecutionTime = Integer.MAX_VALUE;
            info.lastMaxExecutionTime = Integer.MIN_VALUE;

            abortCount = info.abortCount - info.lastAbortCount;
            info.lastAbortCount = info.abortCount;

            failureCount = info.failureCount - info.lastFailureCount;
            info.lastFailureCount = info.failureCount;
        }

        rowValues[offset + Initiator.CONNECTION_ID.ordinal()] = connectionId;
        rowValues[offset + Initiator.CONNECTION_HOSTNAME.ordinal()] = info.connectionHostname;
        rowValues[offset + Initiator.PROCEDURE_NAME.ordinal()] = procName;
        rowValues[offset + Initiator.INVOCATIONS.ordinal()] = invocationCount;
        rowValues[offset + Initiator.AVG_EXECUTION_TIME.ordinal()] = (int)(totalExecutionTime / invocationCount);
        rowValues[offset + Initiator.MIN_EXECUTION_TIME.ordinal()] = minExecutionTime;
        rowValues[offset + Initiator.MAX_EXECUTION_TIME.ordinal()] = maxExecutionTime;
        rowValues[offset + Initiator.ABORTS.ordinal()] = abortCount;
        rowValues[offset + Initiator.FAILURES.ordinal()] = failureCount;
        return offset + Initiator.values().length;
    }

    private class DummyIterator implements Iterator<Object> {
        private final Iterator<Map.Entry<Long, Map<String, InvocationInfo>>> outerItr;
        private Iterator<Map.Entry<String, InvocationInfo>> innerItr = null;
        private Map.Entry<Long, Map<String, InvocationInfo>> outerNext = null;
        private Map.Entry<String, InvocationInfo> innerNext = null;
        private final boolean interval;
        private DummyIterator(Iterator<Map.Entry<Long, Map<String, InvocationInfo>>> i, boolean interval) {
            this.outerItr = i;
            this.interval = interval;
        }

        private boolean advanceOuter() {
            if(outerItr.hasNext()) {
                outerNext = outerItr.next();
                // reset innerItr
                innerItr = outerNext.getValue().entrySet().iterator();
                return true;
            }
            outerNext = null;
            return false;
        }

        private boolean advanceInner() {
            if(innerItr.hasNext()) {
                innerNext = innerItr.next();
                return true;
            }
            innerNext = null;
            return false;
        }

        @Override
        public boolean hasNext() {
            if (!interval) {
                if(innerItr == null) {
                    if(advanceOuter()) {
                        return advanceInner();
                    }
                    return false;
                } else {
                    if(advanceInner()) {
                        return true;
                    }
                    if(advanceOuter()) {
                        return advanceInner();
                    }
                    return false;
                }
            }
            if (innerItr == null) {
                advanceOuter();
            }
            // innerItr can be null if connection created but not doing any procedures
            if (innerItr == null || !outerItr.hasNext() && !innerItr.hasNext()) {
                return false;
            } else {
                while (innerNext == null && (outerItr.hasNext() || innerItr.hasNext())) {
                    InvocationInfo info = null;
                    // first, look up at lower level map
                    advanceInner();
                    // not found, advance upper level map itr, and look up in next lower level map
                    if(innerNext == null) {
                        advanceOuter();
                        advanceInner();
                    }
                    info = innerNext.getValue();
                    if(info.invocationCount - info.lastInvocationCount == 0) {
                        innerNext = null;
                        continue;
                    }
                }
                if(innerNext == null) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Object next() {
            return this;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private class AggregatingIterator implements Iterator<Map.Entry<Long, Map<String, InvocationInfo>>> {

        private final Queue<Iterator<Map.Entry<Long, Map<String, InvocationInfo>>>> m_sources;
        private AggregatingIterator(Queue<Iterator<Map.Entry<Long, Map<String, InvocationInfo>>>> sources) {
            m_sources = sources;
        }

        @Override
        public boolean hasNext() {
            Iterator<Map.Entry<Long, Map<String, InvocationInfo>>> i = null;
            while ((i = m_sources.peek()) != null) {
                if (i.hasNext()) return true;
                m_sources.remove();
            }
            return false;
        }

        @Override
        public Map.Entry<Long, Map<String, InvocationInfo>> next() {
            final Iterator<Map.Entry<Long, Map<String, InvocationInfo>>> i = m_sources.peek();
            if (i == null || !i.hasNext()) {
                throw new NoSuchElementException();
            }
            return i.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    protected Iterator<Object> getStatsRowKeyIterator(boolean interval) {
        ArrayDeque<Iterator<Map.Entry<Long, Map<String, InvocationInfo>>>> d =
                new ArrayDeque<Iterator<Map.Entry<Long, Map<String, InvocationInfo>>>>();
        ClientInterface ci = VoltDB.instance().getClientInterface();
        if (ci != null) {
            d.addAll(ci.getIV2InitiatorStats());
        }
        return new DummyIterator(
                new AggregatingIterator(d),
                interval);
    }
}
