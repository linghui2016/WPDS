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
package test;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import boomerang.WeightedForwardQuery;
import com.google.common.collect.Lists;

import boomerang.Query;
import boomerang.debugger.Debugger;
import boomerang.debugger.IDEVizDebugger;
import boomerang.jimple.AllocVal;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import ideal.IDEALAnalysis;
import ideal.IDEALAnalysisDefinition;
import ideal.IDEALSeedSolver;
import soot.Body;
import soot.SceneTransformer;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.nodes.Node;
import test.ExpectedResults.InternalState;
import test.core.selfrunning.AbstractTestingFramework;
import test.core.selfrunning.ImprecisionException;
import typestate.TransitionFunction;
import typestate.finiteautomata.TypeStateMachineWeightFunctions;

public abstract class IDEALTestingFramework extends AbstractTestingFramework{
	protected JimpleBasedInterproceduralCFG icfg;
	private static final boolean FAIL_ON_IMPRECISE = false;


	protected abstract TypeStateMachineWeightFunctions getStateMachine();

	protected IDEALAnalysis<TransitionFunction> createAnalysis() {
		return new IDEALAnalysis<TransitionFunction>(new IDEALAnalysisDefinition<TransitionFunction>() {

			@Override
			public JimpleBasedInterproceduralCFG icfg() {
				return icfg;
			}

			@Override
			public boolean enableStrongUpdates() {
				return false;
			}

			@Override
			public Collection<WeightedForwardQuery<TransitionFunction>> generate(SootMethod method, Unit stmt, Collection<SootMethod> calledMethod) {
				return  IDEALTestingFramework.this.getStateMachine().generateSeed(method, stmt, calledMethod);
			}

			@Override
			public WeightFunctions<Statement, Val, Statement, TransitionFunction> weightFunctions() {
				return IDEALTestingFramework.this.getStateMachine();
			}
			
			@Override
			public Debugger<TransitionFunction> debugger() {
				return new IDEVizDebugger<>(ideVizFile,icfg);
			}
		});
	}

	@Override
	protected SceneTransformer createAnalysisTransformer() throws ImprecisionException {
		return new SceneTransformer() {
			protected void internalTransform(String phaseName, @SuppressWarnings("rawtypes") Map options) {
				icfg = new JimpleBasedInterproceduralCFG(true);
				Set<Assertion> expectedResults = parseExpectedQueryResults(sootTestMethod);
				System.out.println(sootTestMethod.getActiveBody());
				TestingResultReporter testingResultReporter = new TestingResultReporter(expectedResults);
				
				Map<WeightedForwardQuery<TransitionFunction>, IDEALSeedSolver<TransitionFunction>> seedToSolvers = executeAnalysis();
				for(WeightedForwardQuery<TransitionFunction> seed : seedToSolvers.keySet()){
					for(Query q : seedToSolvers.get(seed).getPhase2Solver().getSolvers().keySet()){
						testingResultReporter.onSeedFinished(q.asNode(), seedToSolvers.get(seed).getPhase2Solver().getSolvers().getOrCreate(q));
					}
				}
				List<Assertion> unsound = Lists.newLinkedList();
				List<Assertion> imprecise = Lists.newLinkedList();
				for (Assertion r : expectedResults) {
					if (!r.isSatisfied()) {
						unsound.add(r);
					}
				}
				for (Assertion r : expectedResults) {
					if (r.isImprecise()) {
						imprecise.add(r);
					}
				}
				if (!unsound.isEmpty())
					throw new RuntimeException("Unsound results: " + unsound);
				if (!imprecise.isEmpty() && FAIL_ON_IMPRECISE) {
					throw new ImprecisionException("Imprecise results: " + imprecise);
				}
			}
		};
	}

	protected Map<WeightedForwardQuery<TransitionFunction>, IDEALSeedSolver<TransitionFunction>> executeAnalysis() {
		return IDEALTestingFramework.this.createAnalysis().run();
	}

	private Set<Assertion> parseExpectedQueryResults(SootMethod sootTestMethod) {
		Set<Assertion> results = new HashSet<>();
		parseExpectedQueryResults(sootTestMethod, results, new HashSet<SootMethod>());
		return results;
	}

	private void parseExpectedQueryResults(SootMethod m, Set<Assertion> queries, Set<SootMethod> visited) {
		if (!m.hasActiveBody() || visited.contains(m))
			return;
		visited.add(m);
		Body activeBody = m.getActiveBody();
		for (Unit callSite : icfg.getCallsFromWithin(m)) {
			for (SootMethod callee : icfg.getCalleesOfCallAt(callSite))
				parseExpectedQueryResults(callee, queries, visited);
		}
		for (Unit u : activeBody.getUnits()) {
			if (!(u instanceof Stmt))
				continue;

			Stmt stmt = (Stmt) u;
			if (!(stmt.containsInvokeExpr()))
				continue;
			InvokeExpr invokeExpr = stmt.getInvokeExpr();
			String invocationName = invokeExpr.getMethod().getName();
			if (!invocationName.startsWith("mayBeIn") && !invocationName.startsWith("mustBeIn"))
				continue;
			Value param = invokeExpr.getArg(0);
			Val val = new Val(param, m);
			if (invocationName.startsWith("mayBeIn")) {
				if (invocationName.contains("Error"))
					queries.add(new MayBe(stmt, val, InternalState.ERROR));
				else
					queries.add(new MayBe(stmt, val, InternalState.ACCEPTING));
			} else if (invocationName.startsWith("mustBeIn")) {
				if (invocationName.contains("Error"))
					queries.add(new MustBe(stmt, val, InternalState.ERROR));
				else
					queries.add(new MustBe(stmt, val, InternalState.ACCEPTING));
			}
		}
	}

	/**
	 * The methods parameter describes the variable that a query is issued for.
	 * Note: We misuse the @Deprecated annotation to highlight the method in the
	 * Code.
	 */

	protected static void mayBeInErrorState(Object variable) {

	}

	protected static void mustBeInErrorState(Object variable) {

	}

	protected static void mayBeInAcceptingState(Object variable) {

	}

	protected static void mustBeInAcceptingState(Object variable) {
	}

	/**
	 * This method can be used in test cases to create branching. It is not
	 * optimized away.
	 * 
	 * @return
	 */
	protected boolean staticallyUnknown() {
		return true;
	}

}
