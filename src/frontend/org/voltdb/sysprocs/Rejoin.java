/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
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

package org.voltdb.sysprocs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.voltdb.BackendTarget;
import org.voltdb.DependencyPair;
import org.voltdb.ExecutionSite.SystemProcedureExecutionContext;
import org.voltdb.HsqlBackend;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.SiteProcedureConnection;
import org.voltdb.VoltDB;
import org.voltdb.VoltDBInterface;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Site;
import org.voltdb.dtxn.DtxnConstants;
import org.voltdb.messaging.HostMessenger;

@ProcInfo(singlePartition = false)
public class Rejoin extends VoltSystemProcedure {

    private static final int DEP_rejoinAllNodeWork = (int)
        SysProcFragmentId.PF_rejoinPrepare | DtxnConstants.MULTIPARTITION_DEPENDENCY;

    private static final int DEP_rejoinAggregate = (int)
        SysProcFragmentId.PF_rejoinAggregate;

    @Override
    public void init(int numberOfPartitions, SiteProcedureConnection site,
            Procedure catProc, BackendTarget eeType, HsqlBackend hsql, Cluster cluster) {
        super.init(numberOfPartitions, site, catProc, eeType, hsql, cluster);
        site.registerPlanFragment(SysProcFragmentId.PF_rejoinPrepare, this);
        site.registerPlanFragment(SysProcFragmentId.PF_rejoinCommit, this);
        site.registerPlanFragment(SysProcFragmentId.PF_rejoinRollback, this);
        site.registerPlanFragment(SysProcFragmentId.PF_rejoinAggregate, this);
    }

    /**
     *
     * @param rejoiningHostname
     * @param portToConnect
     * @return
     */
    VoltTable phaseOnePrepare(int hostId, int rejoinHostId, String rejoiningHostname, int portToConnect) {
        // verify valid hostId

        // connect
        VoltTable retval = new VoltTable(
                new ColumnInfo("HostId", VoltType.INTEGER),
                new ColumnInfo("Error", VoltType.STRING) // string on failure, null on success
        );

        String error = VoltDB.instance().doRejoinPrepare(getTransactionId(), rejoinHostId, rejoiningHostname, portToConnect);

        retval.addRow(hostId, error);

        return retval;
    }

    VoltTable aggregateResults(List<VoltTable> dependencies) {
        VoltTable retval = new VoltTable(
                new ColumnInfo("HostId", VoltType.INTEGER),
                new ColumnInfo("Error", VoltType.STRING) // string on failure, null on success
        );

        // take all the one-row result tables and collect any errors
        for (VoltTable table : dependencies) {
            table.advanceRow();
            String errMsg = table.getString(1);
            int hostId = (int) table.getLong(0);
            if (errMsg != null) {
                retval.addRow(hostId, errMsg);
            }
        }

        return retval;
    }

    VoltTable phaseThreeCommit(int rejoinHostId, SystemProcedureExecutionContext context) {
        VoltTable retval = new VoltTable(
                new ColumnInfo("HostId", VoltType.INTEGER),
                new ColumnInfo("Error", VoltType.STRING) // string on failure, null on success
        );

        ArrayList<Integer> rejoiningSiteIds = new ArrayList<Integer>();
        ArrayList<Integer> rejoiningExecSiteIds = new ArrayList<Integer>();
        Cluster cluster = context.getCluster();
        for (Site site : cluster.getSites()) {
            int siteId = Integer.parseInt(site.getTypeName());
            int hostId = Integer.parseInt(site.getHost().getTypeName());
            if (hostId == rejoinHostId) {
                assert(site.getIsup() == false);
                rejoiningSiteIds.add(siteId);
                if (site.getIsexec() == true) {
                    rejoiningExecSiteIds.add(siteId);
                }
            }
        }
        assert(rejoiningSiteIds.size() > 0);

        StringBuilder sb = new StringBuilder();
        for (int siteId : rejoiningSiteIds)
        {
            sb.append("set ");
            String site_path = VoltDB.instance().getCatalogContext().catalog.
                               getClusters().get("cluster").getSites().
                               get(Integer.toString(siteId)).getPath();
            sb.append(site_path).append(" ").append("isUp true");
            sb.append("\n");
        }
        String catalogDiffCommands = sb.toString();

        String error = VoltDB.instance().doRejoinCommitOrRollback(getTransactionId(), true);
        context.getExecutionSite().updateClusterState(catalogDiffCommands);

        retval.addRow(Integer.parseInt(context.getSite().getTypeName()), error);

        return retval;
    }

    VoltTable phaseThreeRollback(int hostId) {
        VoltTable retval = new VoltTable(
                new ColumnInfo("HostId", VoltType.INTEGER),
                new ColumnInfo("Error", VoltType.STRING) // string on failure, null on success
        );

        String error = VoltDB.instance().doRejoinCommitOrRollback(getTransactionId(), false);

        retval.addRow(hostId, error);
        return retval;
    }

    @Override
    public DependencyPair executePlanFragment(
            HashMap<Integer, List<VoltTable>> dependencies, long fragmentId,
            ParameterSet params, SystemProcedureExecutionContext context) {

        HostMessenger messenger = (HostMessenger) VoltDB.instance().getMessenger();
        int hostId = messenger.getHostId();
        Object[] oparams = params.toArray();
        VoltTable depResult = null;

        if (fragmentId == SysProcFragmentId.PF_rejoinPrepare) {
            int rejoinHostId = (Integer) oparams[0];
            String rejoiningHostName = (String) oparams[1];
            int portToConnect = (Integer) oparams[2];

            depResult = phaseOnePrepare(hostId, rejoinHostId, rejoiningHostName, portToConnect);
            return new DependencyPair(DEP_rejoinAllNodeWork, depResult);
        }
        else if (fragmentId == SysProcFragmentId.PF_rejoinCommit) {
            int rejoinHostId = (Integer) oparams[0];
            depResult = phaseThreeCommit(rejoinHostId, context);
            return new DependencyPair(DEP_rejoinAllNodeWork, depResult);
        }
        else if (fragmentId == SysProcFragmentId.PF_rejoinRollback) {
            depResult = phaseThreeRollback(hostId);
            return new DependencyPair(DEP_rejoinAllNodeWork, depResult);
        }
        else if (fragmentId == SysProcFragmentId.PF_rejoinAggregate) {
            depResult = aggregateResults(dependencies.get(DEP_rejoinAllNodeWork));
            return new DependencyPair(DEP_rejoinAggregate, depResult);
        }
        else {
            // really shouldn't ever get here
            assert(false);
            return null;
        }
    }

    public long run(SystemProcedureExecutionContext ctx, String rejoiningHostname, int portToConnect) {

        // pick a hostid to replace
        VoltDBInterface instance = VoltDB.instance();
        HostMessenger hm = instance.getHostMessenger();
        int hostId = hm.findDownHostId();
        // if there are no down hosts from the point of view of this node
        if (hostId == -1) {
            throw new VoltAbortException("Unable to find down node to replace.");
        }

        // start the chain of joining plan fragments

        VoltTable[] results;
        SynthesizedPlanFragment[] pfs = new SynthesizedPlanFragment[2];
        // create a work fragment to prepare to add the node to the cluster
        pfs[1] = new SynthesizedPlanFragment();
        pfs[1].fragmentId = SysProcFragmentId.PF_rejoinPrepare;
        pfs[1].outputDepId = DEP_rejoinAllNodeWork;
        pfs[1].inputDepIds = new int[]{};
        pfs[1].multipartition = true;
        pfs[1].parameters = new ParameterSet();
        pfs[1].parameters.setParameters(hostId, rejoiningHostname, portToConnect);

        // create a work fragment to aggregate the results.
        // Set the MULTIPARTITION_DEPENDENCY bit to require a dependency from every site.
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].fragmentId = SysProcFragmentId.PF_rejoinAggregate;
        pfs[0].outputDepId = DEP_rejoinAggregate;
        pfs[0].inputDepIds = new int[]{ DEP_rejoinAllNodeWork };
        pfs[0].multipartition = false;
        pfs[0].parameters = new ParameterSet();

        // distribute and execute these fragments providing pfs and id of the
        // aggregator's output dependency table.
        results = executeSysProcPlanFragments(pfs, DEP_rejoinAggregate);
        boolean shouldCommit = true;

        // figure out if there were any problems
        // base the commit/rollback decision on this
        assert(results.length == 1);
        VoltTable errorTable = results[0];
        if (errorTable.getRowCount() > 0)
            shouldCommit = false;

        pfs = new SynthesizedPlanFragment[2];
        // create a work fragment to prepare to add the node to the cluster
        pfs[1] = new SynthesizedPlanFragment();
        if (shouldCommit)
            pfs[1].fragmentId = SysProcFragmentId.PF_rejoinCommit;
        else
            pfs[1].fragmentId = SysProcFragmentId.PF_rejoinRollback;
        pfs[1].outputDepId = DEP_rejoinAllNodeWork;
        pfs[1].inputDepIds = new int[]{};
        pfs[1].multipartition = true;
        pfs[1].parameters = new ParameterSet();
        pfs[1].parameters.setParameters(hostId);

        // create a work fragment to aggregate the results.
        // Set the MULTIPARTITION_DEPENDENCY bit to require a dependency from every site.
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].fragmentId = SysProcFragmentId.PF_rejoinAggregate;
        pfs[0].outputDepId = DEP_rejoinAggregate;
        pfs[0].inputDepIds = new int[]{ DEP_rejoinAllNodeWork };
        pfs[0].multipartition = false;
        pfs[0].parameters = new ParameterSet();

        results = executeSysProcPlanFragments(pfs, DEP_rejoinAggregate);

        assert(results.length == 1);
        if (errorTable.getRowCount() > 0) {
            // this is a bad situation
        }

        return 0;
    }

}
