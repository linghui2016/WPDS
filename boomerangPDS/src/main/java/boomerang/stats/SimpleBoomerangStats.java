/*******************************************************************************
 * Copyright (c) 2018 Fraunhofer IEM, Paderborn, Germany.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *  
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Johannes Spaeth - initial API and implementation
 *******************************************************************************/
package boomerang.stats;

import boomerang.ForwardQuery;
import boomerang.Query;
import boomerang.WeightedBoomerang;
import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.solver.AbstractBoomerangSolver;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import soot.SootMethod;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import wpds.impl.Transition;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.WPAUpdateListener;

import java.util.Map;
import java.util.Set;

/**
 * Created by johannesspath on 06.12.17.
 */
public class SimpleBoomerangStats<W extends Weight> implements IBoomerangStats<W> {

    private Map<Query, AbstractBoomerangSolver<W>> queries = Maps.newHashMap();
    private Set<SootMethod> callVisitedMethods = Sets.newHashSet();
    private Set<SootMethod> fieldVisitedMethods = Sets.newHashSet();

    @Override
    public void registerSolver(Query key, final AbstractBoomerangSolver<W> solver) {
        if (queries.containsKey(key)) {
            return;
        }
        queries.put(key, solver);

        solver.getCallAutomaton().registerListener(new WPAUpdateListener<Statement, INode<Val>, W>() {
            @Override
            public void onWeightAdded(Transition<Statement, INode<Val>> t, W w,
                                      WeightedPAutomaton<Statement, INode<Val>, W> aut) {
                callVisitedMethods.add(t.getLabel().getMethod());
            }
        });
        solver.getFieldAutomaton().registerListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {
            @Override
            public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
                                      WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
                fieldVisitedMethods.add(t.getStart().fact().stmt().getMethod());
            }
        });

    }

    @Override
    public void registerCallSitePOI(WeightedBoomerang<W>.ForwardCallSitePOI key) {

    }

    @Override
    public void registerFieldWritePOI(WeightedBoomerang<W>.FieldWritePOI key) {

    }

    @Override
    public void registerFieldReadPOI(WeightedBoomerang<W>.FieldReadPOI key){
    }

    public String toString(){
        String s = "=========== Boomerang Stats =============\n";
        int forwardQuery = 0;
        int backwardQuery = 0;
        for(Query q : queries.keySet()){
            if(q instanceof ForwardQuery) {
                forwardQuery++;
            }
            else
                backwardQuery++;
        }
        s+= String.format("Queries (Forward/Backward/Total): \t\t %s/%s/%s\n", forwardQuery,backwardQuery,queries.keySet().size());
        s+= String.format("Visited Methods (Field/Call): \t\t %s/%s/(%s/%s)\n", fieldVisitedMethods.size(), callVisitedMethods.size(), Sets.difference(fieldVisitedMethods,callVisitedMethods).size(), Sets.difference(callVisitedMethods,fieldVisitedMethods).size());
        s+="\n";
        return s;
    }



    @Override
    public Set<SootMethod> getCallVisitedMethods() {
        return Sets.newHashSet(callVisitedMethods);
    }
}
