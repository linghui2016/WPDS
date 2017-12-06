package boomerang;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;

import boomerang.customize.BackwardEmptyCalleeFlow;
import boomerang.customize.EmptyCalleeFlow;
import boomerang.customize.ForwardEmptyCalleeFlow;
import boomerang.debugger.Debugger;
import boomerang.jimple.AllocVal;
import boomerang.jimple.Field;
import boomerang.jimple.Statement;
import boomerang.jimple.Val;
import boomerang.poi.AbstractPOI;
import boomerang.poi.PointOfIndirection;
import boomerang.solver.AbstractBoomerangSolver;
import boomerang.solver.BackwardBoomerangSolver;
import boomerang.solver.ForwardBoomerangSolver;
import boomerang.solver.MethodBasedFieldTransitionListener;
import boomerang.solver.ReachableMethodListener;
import boomerang.solver.StatementBasedFieldTransitionListener;
import boomerang.stats.BoomerangStats;
import heros.utilities.DefaultValueMap;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.ide.icfg.BackwardsInterproceduralCFG;
import soot.jimple.toolkits.ide.icfg.BiDiInterproceduralCFG;
import sync.pds.solver.SyncPDSUpdateListener;
import sync.pds.solver.SyncStatePDSUpdateListener;
import sync.pds.solver.WeightFunctions;
import sync.pds.solver.WitnessNode;
import sync.pds.solver.nodes.AllocNode;
import sync.pds.solver.nodes.GeneratedState;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import sync.pds.solver.nodes.SingleNode;
import wpds.impl.ConnectPushListener;
import wpds.impl.NestedWeightedPAutomatons;
import wpds.impl.SummaryNestedWeightedPAutomatons;
import wpds.impl.Transition;
import wpds.impl.UnbalancedPopListener;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.ReachabilityListener;
import wpds.interfaces.State;
import wpds.interfaces.WPAStateListener;
import wpds.interfaces.WPAUpdateListener;

public abstract class WeightedBoomerang<W extends Weight> implements MethodReachableQueue {
	public static final boolean DEBUG = false;
	private Map<Entry<INode<Node<Statement, Val>>, Field>, INode<Node<Statement, Val>>> genField = new HashMap<>();
	private long lastTick;
	private final DefaultValueMap<Query, AbstractBoomerangSolver<W>> queryToSolvers = new DefaultValueMap<Query, AbstractBoomerangSolver<W>>() {

		@Override
		protected AbstractBoomerangSolver<W> createItem(final Query key) {
			final AbstractBoomerangSolver<W> solver;
			if (key instanceof BackwardQuery) {
				if(DEBUG)
					System.out.println("Backward solving query: " + key);
				solver = createBackwardSolver((BackwardQuery) key);
			} else {
				if(DEBUG)
					System.out.println("Forward solving query: " + key);
				solver = createForwardSolver((ForwardQuery) key);
				if (key.getType() instanceof RefType) {
					addAllocationType((RefType) key.getType());
				}
			}
			
			solver.getCallAutomaton()
					.registerUnbalancedPopListener(new UnbalancedPopListener<Statement, INode<Val>, W>() {

				@Override
				public void unbalancedPop(final INode<Val> returningFact, final Transition<Statement,INode<Val>> trans,
						final W weight) {
					Statement exitStmt = trans.getLabel();
					SootMethod callee = exitStmt.getMethod();
					if (!callee.isStaticInitializer()) {
						for (Unit callSite : WeightedBoomerang.this.icfg().getCallersOf(callee)) {
							if(!((Stmt) callSite).containsInvokeExpr())
								continue;
							
							//TODO if BackwardSolver && unbalanced...
							if(options.onTheFlyCallGraph() && solver instanceof  BackwardBoomerangSolver){
								final SootMethod caller = WeightedBoomerang.this.icfg().getMethodOf(callSite);
								addTypeReachable(caller);
							}
							final Statement callStatement = new Statement((Stmt) callSite,
									WeightedBoomerang.this.icfg().getMethodOf(callSite));

							boolean valueUsedInStatement = solver.valueUsedInStatement((Stmt) callSite, returningFact.fact());
							if(valueUsedInStatement || AbstractBoomerangSolver.assignsValue((Stmt)callSite,returningFact.fact())){
								unbalancedReturnFlow(callStatement, returningFact, trans, weight);
							}
						}
					} else {
						for (SootMethod entryPoint : Scene.v().getEntryPoints()) {
							for (Unit ep : WeightedBoomerang.this.icfg().getStartPointsOf(entryPoint)) {
								final Statement callStatement = new Statement((Stmt) ep,
										WeightedBoomerang.this.icfg().getMethodOf(ep));
								WeightedBoomerang.this.submit(callStatement.getMethod(), new Runnable() {
									@Override
									public void run() {

										Node<Statement, Val> returnedVal = new Node<Statement, Val>(callStatement,
												returningFact.fact());
										solver.setCallingContextReachable(returnedVal);
										solver.getCallAutomaton().addWeightForTransition(new Transition<Statement,INode<Val>>(returningFact,callStatement,solver.getCallAutomaton().getInitialState()),weight);

										final ForwardCallSitePOI callSitePoi = forwardCallSitePOI
												.getOrCreate(new ForwardCallSitePOI(callStatement));
										callSitePoi.returnsFromCall(key);
									}
								});
							}
						}
					}
				}

				private void unbalancedReturnFlow(final Statement callStatement,
						final INode<Val> returningFact, final Transition<Statement, INode<Val>> trans, final W weight) {
					addFlowReachable(callStatement.getMethod());
					WeightedBoomerang.this.submit(callStatement.getMethod(), new Runnable() {
						@Override
						public void run() {
							for (Statement returnSite : solver.getSuccsOf(callStatement)) {
								Node<Statement, Val> returnedVal = new Node<Statement, Val>(returnSite,
										returningFact.fact());
								solver.setCallingContextReachable(returnedVal);
								solver.getCallAutomaton().addWeightForTransition(new Transition<Statement,INode<Val>>(returningFact,returnSite,solver.getCallAutomaton().getInitialState()), weight);

								final ForwardCallSitePOI callSitePoi = forwardCallSitePOI
										.getOrCreate(new ForwardCallSitePOI(callStatement));
								callSitePoi.returnsFromCall(key);
							}
						}

					});
				}

			});
			stats.registerSolver(key, solver);
			solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {

				@Override
				public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> reachableNode) {
					if(options.analysisTimeoutMS() > 0){
						long elapsed = analysisWatch.elapsed(TimeUnit.MILLISECONDS);
						if(elapsed - lastTick > 3000){
							System.err.println(stats);
							lastTick = elapsed;
						}
						if(options.analysisTimeoutMS() < elapsed){
							analysisWatch.stop();
							throw new BoomerangTimeoutException(elapsed,stats);
						}
					}
				}
			});
			return solver;
		}
	};

	private BackwardsInterproceduralCFG bwicfg;
	private EmptyCalleeFlow forwardEmptyCalleeFlow = new ForwardEmptyCalleeFlow();
	private EmptyCalleeFlow backwardEmptyCalleeFlow = new BackwardEmptyCalleeFlow();
	protected BoomerangStats<W> stats = new BoomerangStats<>();
	
	private Collection<RefType> allocatedTypes = Sets.newHashSet();
	private Multimap<SootMethod, Runnable> queuedReachableMethod = HashMultimap.create();
	private Collection<SootMethod> reachableMethods = Sets.newHashSet();
	private NestedWeightedPAutomatons<Statement, INode<Val>, W> backwardCallSummaries = new SummaryNestedWeightedPAutomatons<>();
	private NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> backwardFieldSummaries = new SummaryNestedWeightedPAutomatons<>();
	private NestedWeightedPAutomatons<Statement, INode<Val>, W> forwardCallSummaries = new SummaryNestedWeightedPAutomatons<>();
	private NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> forwardFieldSummaries = new SummaryNestedWeightedPAutomatons<>();
	private DefaultValueMap<FieldWritePOI, FieldWritePOI> fieldWrites = new DefaultValueMap<FieldWritePOI, FieldWritePOI>() {
		@Override
		protected FieldWritePOI createItem(FieldWritePOI key) {
			stats.registerFieldWritePOI(key);
			return key;
		}
	};
	private DefaultValueMap<FieldReadPOI, FieldReadPOI> fieldReads = new DefaultValueMap<FieldReadPOI, FieldReadPOI>() {
		@Override
		protected FieldReadPOI createItem(FieldReadPOI key) {
			stats.registerFieldReadPOI(key);
			return key;
		}
	};
	private DefaultValueMap<ForwardCallSitePOI, ForwardCallSitePOI> forwardCallSitePOI = new DefaultValueMap<ForwardCallSitePOI, ForwardCallSitePOI>() {
		@Override
		protected ForwardCallSitePOI createItem(ForwardCallSitePOI key) {
			stats.registerCallSitePOI(key);
			return key;
		}
	};
	private Set<ReachableMethodListener<W>> reachableMethodListeners = Sets.newHashSet();
	private Set<SootMethod> typeReachable = Sets.newHashSet();
	private Set<SootMethod> flowReachable = Sets.newHashSet();
	protected final BoomerangOptions options;
	private Debugger<W> debugger;
	private Stopwatch analysisWatch = Stopwatch.createUnstarted();
	private Collection<SootMethod> unbalancedCallers = Sets.newHashSet();
	public WeightedBoomerang(BoomerangOptions options){
		this.options = options;
	}

	public WeightedBoomerang(){
		this(new DefaultBoomerangOptions());
	}
	
	protected AbstractBoomerangSolver<W> createBackwardSolver(final BackwardQuery backwardQuery) {
		final BackwardBoomerangSolver<W> solver = new BackwardBoomerangSolver<W>(WeightedBoomerang.this, bwicfg(),
				backwardQuery, genField, options, createCallSummaries(backwardQuery, backwardCallSummaries),
				createFieldSummaries(backwardQuery, backwardFieldSummaries)) {

			@Override
			protected void callBypass(Statement callSite, Statement returnSite, Val value) {
			}
			@Override
			protected Collection<? extends State> computeCallFlow(SootMethod caller, Statement returnSite,
					Statement callSite, InvokeExpr invokeExpr, Val fact, SootMethod callee, Stmt calleeSp) {
				return super.computeCallFlow(caller, returnSite, callSite, invokeExpr, fact, callee, calleeSp);
			}
			
			@Override
			protected void onCallFlow(SootMethod callee, Stmt callSite, Val value,
							Collection<? extends State> res) {
				if (!res.isEmpty()) {
					addReachable(callee);
				}
				super.onCallFlow(callee, callSite, value, res);
			}

			@Override
			protected Collection<? extends State> getEmptyCalleeFlow(SootMethod caller, Stmt callSite, Val value,
					Stmt returnSite) {
				return backwardEmptyCalleeFlow.getEmptyCalleeFlow(caller, callSite, value, returnSite);
			}

			@Override
			protected void onReturnFromCall(final Statement callSite, Statement returnSite,
					final Node<Statement, Val> returnedNode, final boolean unbalanced) {
				final ForwardCallSitePOI callSitePoi = forwardCallSitePOI.getOrCreate(new ForwardCallSitePOI(callSite));
				callSitePoi.returnsFromCall(backwardQuery);
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, W> getFieldWeights() {
				return WeightedBoomerang.this.getBackwardFieldWeights();
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, W> getCallWeights() {
				return WeightedBoomerang.this.getBackwardCallWeights();
			}

		};
		solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {
			@Override
			public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> node) {
				Optional<AllocVal> allocNode = isAllocationNode(node.stmt(), node.fact());
				if (allocNode.isPresent()) {
					forwardSolve(new ForwardQuery(node.stmt(), allocNode.get()));
				}
				if (isFieldStore(node.stmt())) {
				} else if (isArrayStore(node.stmt())) {
					// forwardHandleFieldWrite(node,
					// createArrayFieldStore(node.stmt()), sourceQuery);
				} else if (isFieldLoad(node.stmt())) {
					backwardHandleFieldRead(node, createFieldLoad(node.stmt()), backwardQuery);
				}
				if (isBackwardEnterCall(node.stmt())) {
					backwardHandleEnterCall(node, forwardCallSitePOI.getOrCreate(new ForwardCallSitePOI(node.stmt())),
								backwardQuery);
				}
			}

		});
		return solver;
	}


	protected void backwardHandleEnterCall(WitnessNode<Statement, Val, Field> node, ForwardCallSitePOI returnSite,
			BackwardQuery backwardQuery) {
		returnSite.returnsFromCall(backwardQuery);
	}

	protected boolean isBackwardEnterCall(Statement stmt) {
		return icfg().isExitStmt(stmt.getUnit().get());
	}

	protected Optional<AllocVal> isAllocationNode(Statement s, Val fact) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			return options.getAllocationVal(s.getMethod(),stmt, fact, icfg());
		}
		return Optional.absent();
	}

	protected ForwardBoomerangSolver<W> createForwardSolver(final ForwardQuery sourceQuery) {
		final ForwardBoomerangSolver<W> solver = new ForwardBoomerangSolver<W>(WeightedBoomerang.this, icfg(), sourceQuery,
				genField, options, createCallSummaries(sourceQuery, forwardCallSummaries),
				 createFieldSummaries(sourceQuery, forwardFieldSummaries)) {
			@Override
			protected void onReturnFromCall(Statement callSite, Statement returnSite,
					final Node<Statement, Val> returnedNode, final boolean unbalanced) {
			}

			@Override
			protected void callBypass(Statement callSite, Statement returnSite, Val value) {
				SootMethod calledMethod = callSite.getUnit().get().getInvokeExpr().getMethod();
				if(calledMethod.isStatic()){
					addFlowReachable(calledMethod);
				}
				if(value.isStatic())
					return;
				ForwardCallSitePOI callSitePoi = forwardCallSitePOI.getOrCreate(new ForwardCallSitePOI(callSite));
				callSitePoi.addByPassingAllocation(sourceQuery);
			}

			@Override
			protected void onCallFlow(SootMethod callee, Stmt callSite, Val value, Collection<? extends State> res) {
				if (!res.isEmpty() && options.onTheFlyCallGraph()) {
					addFlowReachable(callee);
					boolean isThisValue = false;
					if(callSite.getInvokeExpr() instanceof InstanceInvokeExpr){
						isThisValue = ((InstanceInvokeExpr) callSite.getInvokeExpr()).getBase().equals(value.value());
					}
					if(!isThisValue || !sourceQuery.var().isNewExpr()){
						addReachable(callee);
					}
				}
				super.onCallFlow(callee, callSite, value, res);
			}

			@Override
			protected Collection<? extends State> getEmptyCalleeFlow(SootMethod caller, Stmt callSite, Val value,
					Stmt returnSite) {
				return forwardEmptyCalleeFlow.getEmptyCalleeFlow(caller, callSite, value, returnSite);
			}

			@Override
			protected WeightFunctions<Statement, Val, Statement, W> getCallWeights() {
				return WeightedBoomerang.this.getForwardCallWeights(sourceQuery);
			}

			@Override
			protected WeightFunctions<Statement, Val, Field, W> getFieldWeights() {
				return WeightedBoomerang.this.getForwardFieldWeights();
			}

		};

		solver.registerListener(new SyncPDSUpdateListener<Statement, Val, Field>() {
			@Override
			public void onReachableNodeAdded(WitnessNode<Statement, Val, Field> node) {
				if (isFieldStore(node.stmt())) {
					forwardHandleFieldWrite(node, createFieldStore(node.stmt()), sourceQuery);
				} else if (isArrayStore(node.stmt())) {
					if (options.arrayFlows()) {
						forwardHandleFieldWrite(node, createArrayFieldStore(node.stmt()), sourceQuery);
					}
				} else if (isFieldLoad(node.stmt())) {
					forwardHandleFieldLoad(node, createFieldLoad(node.stmt()), sourceQuery);
				}
				if (isBackwardEnterCall(node.stmt())) {
					if(!(WeightedBoomerang.this instanceof WholeProgramBoomerang)){
						forwardCallSitePOI.getOrCreate(new ForwardCallSitePOI(node.stmt()))
								.addByPassingAllocation(sourceQuery);
					}
				}
			}
		});
		solver.getCallAutomaton().registerConnectPushListener(new ConnectPushListener<Statement, INode<Val>, W>() {

			@Override
			public void connect(Statement callSite, Statement returnSite, INode<Val> returnedFact, W returnedWeight) {
				if(!callSite.getMethod().equals(returnSite.getMethod()))
					return;
				if(!returnedFact.fact().isStatic() && !returnedFact.fact().m().equals(callSite.getMethod()))
					return;
				final ForwardCallSitePOI callSitePoi = forwardCallSitePOI.getOrCreate(new ForwardCallSitePOI(callSite));
				callSitePoi.returnsFromCall(sourceQuery);
			}
		});

		return solver;
	}

	private NestedWeightedPAutomatons<Statement, INode<Val>, W> createCallSummaries(final Query sourceQuery,
			final NestedWeightedPAutomatons<Statement, INode<Val>, W> summaries) {
		return new NestedWeightedPAutomatons<Statement, INode<Val>, W>() {

			@Override
			public void putSummaryAutomaton(INode<Val> target, WeightedPAutomaton<Statement, INode<Val>, W> aut) {
				summaries.putSummaryAutomaton(target, aut);
			}

			@Override
			public WeightedPAutomaton<Statement, INode<Val>, W> getSummaryAutomaton(INode<Val> target) {
				if (target.fact().equals(sourceQuery.var())) {
					return queryToSolvers.getOrCreate(sourceQuery).getCallAutomaton();
				}
				return summaries.getSummaryAutomaton(target);
			}
		};
	}

	private NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> createFieldSummaries(final Query query,
			final NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W> summaries) {
		return new NestedWeightedPAutomatons<Field, INode<Node<Statement, Val>>, W>() {

			@Override
			public void putSummaryAutomaton(INode<Node<Statement, Val>> target,
					WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
				summaries.putSummaryAutomaton(target, aut);
			}

			@Override
			public WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> getSummaryAutomaton(
					INode<Node<Statement, Val>> target) {
				if (target.fact().equals(query.asNode())) {
					return queryToSolvers.getOrCreate(query).getFieldAutomaton();
				}
				return summaries.getSummaryAutomaton(target);
			}

		};
	}

	protected FieldReadPOI createFieldLoad(Statement s) {
		Stmt stmt = s.getUnit().get();
		AssignStmt as = (AssignStmt) stmt;
		InstanceFieldRef ifr = (InstanceFieldRef) as.getRightOp();
		Val base = new Val(ifr.getBase(), icfg().getMethodOf(as));
		Field field = new Field(ifr.getField());
		return fieldReads
				.getOrCreate(new FieldReadPOI(s, base, field, new Val(as.getLeftOp(), icfg().getMethodOf(as))));
	}

	protected FieldWritePOI createArrayFieldStore(Statement s) {
		Stmt stmt = s.getUnit().get();
		AssignStmt as = (AssignStmt) stmt;
		ArrayRef ifr = (ArrayRef) as.getLeftOp();
		Val base = new Val(ifr.getBase(), icfg().getMethodOf(as));
		Val stored = new Val(as.getRightOp(), icfg().getMethodOf(as));
		return fieldWrites.getOrCreate(new FieldWritePOI(s, base, Field.array(), stored));
	}

	protected FieldWritePOI createFieldStore(Statement s) {
		Stmt stmt = s.getUnit().get();
		AssignStmt as = (AssignStmt) stmt;
		InstanceFieldRef ifr = (InstanceFieldRef) as.getLeftOp();
		Val base = new Val(ifr.getBase(), icfg().getMethodOf(as));
		Val stored = new Val(as.getRightOp(), icfg().getMethodOf(as));
		Field field = new Field(ifr.getField());
		return fieldWrites.getOrCreate(new FieldWritePOI(s, base, field, stored));
	}

	public static boolean isFieldStore(Statement s) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof InstanceFieldRef) {
				return true;
			}
		}
		return false;
	}

	public static boolean isArrayStore(Statement s) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getLeftOp() instanceof ArrayRef) {
				return true;
			}
		}
		return false;
	}

	public static boolean isFieldLoad(Statement s) {
		Optional<Stmt> optUnit = s.getUnit();
		if (optUnit.isPresent()) {
			Stmt stmt = optUnit.get();
			if (stmt instanceof AssignStmt && ((AssignStmt) stmt).getRightOp() instanceof InstanceFieldRef) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void submit(SootMethod method, Runnable runnable) {
		if (reachableMethods.contains(method) || !options.onTheFlyCallGraph()) {
			runnable.run();
		} else {
			queuedReachableMethod.put(method, runnable);
		}
	}

	public void registerReachableMethodListener(ReachableMethodListener<W> reachableMethodListener) {
		if (reachableMethodListeners.add(reachableMethodListener)) {
			for (SootMethod m : Lists.newArrayList(reachableMethods)) {
				reachableMethodListener.reachable(m);
			}
		}
	}

	protected void backwardHandleFieldRead(final WitnessNode<Statement, Val, Field> node, FieldReadPOI fieldRead,
			final BackwardQuery sourceQuery) {
		if (node.fact().equals(fieldRead.getStoredVar())) {
			fieldRead.addFlowAllocation(sourceQuery);
		}
	}

	protected void forwardHandleFieldWrite(final WitnessNode<Statement, Val, Field> node,
			final FieldWritePOI fieldWritePoi, final ForwardQuery sourceQuery) {
		BackwardQuery backwardQuery = new BackwardQuery(node.stmt(), fieldWritePoi.getBaseVar());
		if (node.fact().equals(fieldWritePoi.getStoredVar())) {
			backwardSolve(backwardQuery);
			fieldWritePoi.addFlowAllocation(sourceQuery);
		}
		if (node.fact().equals(fieldWritePoi.getBaseVar())) {
			queryToSolvers.getOrCreate(sourceQuery).getFieldAutomaton().registerListener(
					new TriggerBaseAllocationAtFieldWrite(new SingleNode<Node<Statement, Val>>(node.asNode()),
							fieldWritePoi, sourceQuery));
		}
	}

    public Stopwatch getAnalysisStopwatch() {
        return analysisWatch;
    }

    private class TriggerBaseAllocationAtFieldWrite extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {

		private final PointOfIndirection<Statement, Val, Field> fieldWritePoi;
		private final ForwardQuery sourceQuery;

		public TriggerBaseAllocationAtFieldWrite(INode<Node<Statement, Val>> state,
				PointOfIndirection<Statement, Val, Field> fieldWritePoi, ForwardQuery sourceQuery) {
			super(state);
			this.fieldWritePoi = fieldWritePoi;
			this.sourceQuery = sourceQuery;
		}

		@Override
		public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
			if (isAllocationNode(t.getTarget().fact().fact(), sourceQuery)) {
				fieldWritePoi.addBaseAllocation(sourceQuery);
			}
		}

		@Override
		public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((fieldWritePoi == null) ? 0 : fieldWritePoi.hashCode());
			result = prime * result + ((sourceQuery == null) ? 0 : sourceQuery.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			TriggerBaseAllocationAtFieldWrite other = (TriggerBaseAllocationAtFieldWrite) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (fieldWritePoi == null) {
				if (other.fieldWritePoi != null)
					return false;
			} else if (!fieldWritePoi.equals(other.fieldWritePoi))
				return false;
			if (sourceQuery == null) {
				if (other.sourceQuery != null)
					return false;
			} else if (!sourceQuery.equals(other.sourceQuery))
				return false;
			return true;
		}

		private WeightedBoomerang getOuterType() {
			return WeightedBoomerang.this;
		}

	}

	private void forwardHandleFieldLoad(final WitnessNode<Statement, Val, Field> node, final FieldReadPOI fieldReadPoi,
			final ForwardQuery sourceQuery) {
		if (node.fact().equals(fieldReadPoi.getBaseVar())) {
			queryToSolvers.getOrCreate(sourceQuery).registerFieldTransitionListener(
					new MethodBasedFieldTransitionListener<W>(node.stmt().getMethod()) {
						@Override
						public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
							if (t.getStart().fact().equals(node.asNode())
									&& isAllocationNode(t.getTarget().fact().fact(), sourceQuery)) {
								fieldReadPoi.addBaseAllocation(sourceQuery);
							}
						}
					});
		}
	}

	private boolean isAllocationNode(Val fact, ForwardQuery sourceQuery) {
		return fact.equals(sourceQuery.var());
	}

	private BiDiInterproceduralCFG<Unit, SootMethod> bwicfg() {
		if (bwicfg == null)
			bwicfg = new BackwardsInterproceduralCFG(icfg());
		return bwicfg;
	}

	public void solve(Query query) {
		analysisWatch.reset();
		analysisWatch.start();
		addAllocationType(query.stmt().getMethod().getDeclaringClass().getType());
		addReachable(query.stmt().getMethod());
		unbalancedCallers.add(query.stmt().getMethod());
		if (query instanceof ForwardQuery) {
			forwardSolve((ForwardQuery) query);
		}
		if (query instanceof BackwardQuery) {
			backwardSolve((BackwardQuery) query);
		}
		if(analysisWatch.isRunning())
			analysisWatch.stop();
	}

	protected void backwardSolve(BackwardQuery query) {
		Optional<Stmt> unit = query.asNode().stmt().getUnit();
		AbstractBoomerangSolver<W> solver = queryToSolvers.getOrCreate(query);
		if (unit.isPresent()) {
			for (Unit succ : new BackwardsInterproceduralCFG(icfg()).getSuccsOf(unit.get())) {
				solver.solve(new Node<Statement, Val>(new Statement((Stmt) succ, icfg().getMethodOf(succ)),
						query.asNode().fact()));
			}
		}
	}

	private void forwardSolve(ForwardQuery query) {
		Optional<Stmt> unit = query.asNode().stmt().getUnit();
		AbstractBoomerangSolver<W> solver = queryToSolvers.getOrCreate(query);
		if (unit.isPresent()) {
			for (Unit succ : icfg().getSuccsOf(unit.get())) {
				Node<Statement, Val> source = new Node<Statement, Val>(
						new Statement((Stmt) succ, icfg().getMethodOf(succ)), query.asNode().fact());
				if (isMultiArrayAllocation(unit.get()) && options.arrayFlows()) {
					//TODO fix; adjust as below;
					insertTransition(solver.getFieldAutomaton(),
							new Transition<Field, INode<Node<Statement, Val>>>(
									new SingleNode<Node<Statement, Val>>(source), Field.array(),
									new AllocNode<Node<Statement, Val>>(query.asNode())));
					insertTransition(solver.getFieldAutomaton(),
							new Transition<Field, INode<Node<Statement, Val>>>(
									new SingleNode<Node<Statement, Val>>(source), Field.array(),
									new SingleNode<Node<Statement, Val>>(source)));
				}
				if(isStringAllocation(unit.get())){
//					Scene.v().forceResolve("java.lang.String", SootClass.BODIES);
					SootClass stringClass = Scene.v().getSootClass("java.lang.String");
					if(stringClass.declaresField("char[] value")){
						SootField valueField = stringClass.getField("char[] value");
						SingleNode<Node<Statement, Val>> s = new SingleNode<Node<Statement, Val>>(source);
						INode<Node<Statement, Val>> irState = solver.getFieldAutomaton().createState(s, new Field(valueField));
						insertTransition(solver.getFieldAutomaton(),
								new Transition<Field, INode<Node<Statement, Val>>>(
										new SingleNode<Node<Statement, Val>>(source), new Field(valueField),irState
										));
						insertTransition(solver.getFieldAutomaton(),
								new Transition<Field, INode<Node<Statement, Val>>>(irState, Field.empty(),
										solver.getFieldAutomaton().getInitialState()));
					}
				}
				if(query instanceof WeightedForwardQuery){
					WeightedForwardQuery<W> q = (WeightedForwardQuery<W>) query;
					solver.solve(source,q.weight());
				} else{
					solver.solve(source);
				}
			}
		}
	}

	private boolean isStringAllocation(Stmt stmt) {
		if(stmt instanceof AssignStmt && ((AssignStmt) stmt).getRightOp() instanceof StringConstant){
			return true;
		}
		return false;
	}

	private boolean insertTransition(final WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut,
			Transition<Field, INode<Node<Statement, Val>>> transition) {
		if (!aut.nested()) {
			return aut.addTransition(transition);
		}
		INode<Node<Statement, Val>> target = transition.getTarget();
		if (!(target instanceof GeneratedState)) {
			forwardFieldSummaries.putSummaryAutomaton(target, aut);

			aut.registerListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {

				@Override
				public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
						WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
					if (t.getStart() instanceof GeneratedState) {
						WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> n = forwardFieldSummaries
								.getSummaryAutomaton(t.getStart());
						aut.addNestedAutomaton(n);
					}
				}
			});
			return aut.addTransition(transition);
		}
		final WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> nested = forwardFieldSummaries
				.getSummaryAutomaton(target);
		nested.registerListener(new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {

			@Override
			public void onWeightAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
					WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
				if (t.getStart() instanceof GeneratedState) {
					WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> n = forwardFieldSummaries
							.getSummaryAutomaton(t.getStart());
					aut.addNestedAutomaton(n);
				}
			}
		});
		return nested.addTransition(transition);
	}

	private boolean isMultiArrayAllocation(Stmt stmt) {
		return (stmt instanceof AssignStmt) && ((AssignStmt) stmt).getRightOp() instanceof NewMultiArrayExpr;
	}

	public class FieldWritePOI extends FieldStmtPOI {
		public FieldWritePOI(Statement statement, Val base, Field field, Val stored) {
			super(statement, base, field, stored);
		}

		@Override
		public void execute(final ForwardQuery baseAllocation, final Query flowAllocation) {
			if (flowAllocation instanceof BackwardQuery) {
			} else if (flowAllocation instanceof ForwardQuery) {
				executeImportAliases(baseAllocation, flowAllocation);
			}
		}
	}


	private class HasOutTransition extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {

		private final ForwardQuery byPassing;
		private final Query flowQuery;
		private ForwardCallSitePOI callSite;
		private final Transition<Field, INode<Node<Statement, Val>>> outerT;

		public HasOutTransition(ForwardQuery byPassing, Query flowQuery, ForwardCallSitePOI callSite,
								Transition<Field, INode<Node<Statement, Val>>> outerT) {
			super(outerT.getStart());
			this.byPassing = byPassing;
			this.flowQuery = flowQuery;
			this.callSite = callSite;
			this.outerT = outerT;
		}

		@Override
		public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
			if (t.equals(outerT)) {
				callSite.addAliasPair(flowQuery, byPassing);
			}
		}

		@Override
		public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + ((byPassing == null) ? 0 : byPassing.hashCode());
			result = prime * result + ((callSite == null) ? 0 : callSite.hashCode());
			result = prime * result + ((flowQuery == null) ? 0 : flowQuery.hashCode());
			result = prime * result + ((outerT == null) ? 0 : outerT.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			HasOutTransition other = (HasOutTransition) obj;
			if (byPassing == null) {
				if (other.byPassing != null)
					return false;
			} else if (!byPassing.equals(other.byPassing))
				return false;
			if (callSite == null) {
				if (other.callSite != null)
					return false;
			} else if (!callSite.equals(other.callSite))
				return false;
			if (flowQuery == null) {
				if (other.flowQuery != null)
					return false;
			} else if (!flowQuery.equals(other.flowQuery))
				return false;
			if (outerT == null) {
				if (other.outerT != null)
					return false;
			} else if (!outerT.equals(other.outerT))
				return false;
			return true;
		}
	}
	public class ForwardCallSitePOI {
		private Statement callSite;
		private Set<Query> returnsFromCall = Sets.newHashSet();
		private Set<ForwardQuery> byPassingAllocations = Sets.newHashSet();
		private Multimap<Query,ForwardQuery> aliasQueryPairs = HashMultimap.create();

		public ForwardCallSitePOI(Statement callSite) {
			this.callSite = callSite;
		}


		public void addAliasPair(Query flowQuery, ForwardQuery byPassing) {
			if(aliasQueryPairs.put(flowQuery,byPassing)){
				queryToSolvers.get(byPassing).registerStatementFieldTransitionListener(new ImportFlowAtReturn(callSite,byPassing,flowQuery));
			}
		}
		public void returnsFromCall(final Query flowQuery) {
			if (returnsFromCall.add(flowQuery)) {
				for (final ForwardQuery byPassing : Lists.newArrayList(byPassingAllocations)) {
					eachPair(byPassing, flowQuery);
				}
			}
		}

		private void eachPair(final ForwardQuery byPassing, final Query flowQuery) {
			if (byPassing.equals(flowQuery))
				return;

			queryToSolvers.getOrCreate(byPassing).registerStatementFieldTransitionListener(new StatementBasedFieldTransitionListener<W>(callSite) {
				@Override
				public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
					queryToSolvers.getOrCreate(byPassing).getFieldAutomaton().registerDFSListener(t.getStart(), new ReachabilityListener<Field, INode<Node<Statement, Val>>>() {
						@Override
						public void reachable(Transition<Field, INode<Node<Statement, Val>>> t) {
							queryToSolvers.getOrCreate(byPassing).getFieldAutomaton().registerListener(new HasOutTransition(byPassing,flowQuery,ForwardCallSitePOI.this,t));
						}
					});
				}
			});
		}


		public void addByPassingAllocation(ForwardQuery byPassingAllocation) {
			if (byPassingAllocations.add(byPassingAllocation)) {
				for (Query e : Lists.newArrayList(returnsFromCall)) {
					eachPair(byPassingAllocation, e);
				}
			}
		}

		

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((callSite == null) ? 0 : callSite.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ForwardCallSitePOI other = (ForwardCallSitePOI) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (callSite == null) {
				if (other.callSite != null)
					return false;
			} else if (!callSite.equals(other.callSite))
				return false;
			return true;
		}

		private WeightedBoomerang<W> getOuterType() {
			return WeightedBoomerang.this;
		}

	}

	private class ImportFlowAtReturn extends StatementBasedFieldTransitionListener<W> {
		private final Statement callSite;
		private Query byPassingQuery;
		private Query flowQuery;

		public ImportFlowAtReturn(Statement callSite,
				Query byPassingSolver,
				Query flowSolver) {
			super(callSite);
			this.callSite = callSite;
			this.byPassingQuery = byPassingSolver;
			this.flowQuery = flowSolver;
		}

		@Override
		public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
			if (!(t.getStart() instanceof GeneratedState)) {
				final Val byPassing = t.getStart().fact().fact();
				for(final Statement returnSite : queryToSolvers.get(flowQuery).getSuccsOf(callSite)) {
					queryToSolvers.get(flowQuery).setFieldContextReachable(
							new Node<Statement, Val>(returnSite, byPassing));
					queryToSolvers.get(byPassingQuery).getFieldAutomaton().registerListener(
							new ImportToSolver(new SingleNode<>(new Node<Statement, Val>(returnSite, byPassing)), byPassingQuery, flowQuery));
					queryToSolvers.get(flowQuery).registerStatementFieldTransitionListener(new InjectIndirectNormalFlow<W>(returnSite, byPassing));
				}
			}
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((byPassingQuery == null) ? 0 : byPassingQuery.hashCode());
			result = prime * result + ((flowQuery == null) ? 0 : flowQuery.hashCode());
			result = prime * result + ((callSite == null) ? 0 : callSite.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			ImportFlowAtReturn other = (ImportFlowAtReturn) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (byPassingQuery == null) {
				if (other.byPassingQuery != null)
					return false;
			} else if (!byPassingQuery.equals(other.byPassingQuery))
				return false;
			if (flowQuery == null) {
				if (other.flowQuery != null)
					return false;
			} else if (!flowQuery.equals(other.flowQuery))
				return false;
			if (callSite == null) {
				if (other.callSite != null)
					return false;
			} else if (!callSite.equals(other.callSite))
				return false;
			return true;
		}

		private WeightedBoomerang getOuterType() {
			return WeightedBoomerang.this;
		}

		private class InjectIndirectNormalFlow<W extends Weight> extends StatementBasedFieldTransitionListener<W> {
			private final Statement returnSite;
			private final Val byPassing;

			public InjectIndirectNormalFlow(Statement returnSite, Val byPassing) {
				super(returnSite);
				this.returnSite = returnSite;
				this.byPassing = byPassing;
			}

			@Override
            public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
                if (!(t.getStart() instanceof GeneratedState)) {
                    Val alias = t.getStart().fact().fact();
//                    System.out.println("CallSite: " +callSite + byPassing);
                    queryToSolvers.get(flowQuery).addNormalCallFlow(new Node<Statement, Val>(returnSite,alias),
                        new Node<Statement, Val>(returnSite, byPassing));
//                    System.out.println("ALIAS FLOW" + new Node<Statement, Val>(returnSite,alias) +"  "+
//                            new Node<Statement, Val>(returnSite, byPassing));
                }
            }

			@Override
			public boolean equals(Object o) {
				if (this == o) return true;
				if (o == null || getClass() != o.getClass()) return false;
				if (!super.equals(o)) return false;

				InjectIndirectNormalFlow<?> that = (InjectIndirectNormalFlow<?>) o;

				if (returnSite != null ? !returnSite.equals(that.returnSite) : that.returnSite != null) return false;
				return byPassing != null ? byPassing.equals(that.byPassing) : that.byPassing == null;
			}

			@Override
			public int hashCode() {
				int result = super.hashCode();
				result = 31 * result + (returnSite != null ? returnSite.hashCode() : 0);
				result = 31 * result + (byPassing != null ? byPassing.hashCode() : 0);
				return result;
			}
		}
	}

	public class FieldReadPOI extends FieldStmtPOI {
		public FieldReadPOI(Statement statement, Val base, Field field, Val stored) {
			super(statement, base, field, stored);
		}

		@Override
		public void execute(final ForwardQuery baseAllocation, final Query flowAllocation) {
			if (WeightedBoomerang.this instanceof WholeProgramBoomerang)
				throw new RuntimeException("should not be invoked!");
			if (flowAllocation instanceof ForwardQuery) {
			} else if (flowAllocation instanceof BackwardQuery) {
				executeImportAliases(baseAllocation, flowAllocation);
			}
		}
	}

	private abstract class FieldStmtPOI extends AbstractPOI<Statement, Val, Field> {
		private Multimap<Query, Query> importGraph = HashMultimap.create();

		public FieldStmtPOI(Statement statement, Val base, Field field, Val storedVar) {
			super(statement, base, field, storedVar);
		}

		private boolean introducesLoop(ForwardQuery baseAllocation, Query flowAllocation) {
			importGraph.put(baseAllocation, flowAllocation);
			LinkedList<Query> worklist = Lists.newLinkedList();
			worklist.add(flowAllocation);
			Set<Query> visited = Sets.newHashSet();
			while (!worklist.isEmpty()) {
				Query curr = worklist.pop();
				if (visited.add(curr)) {
					worklist.addAll(importGraph.get(curr));
				}
			}
			return visited.contains(baseAllocation);
		}

		protected void executeImportAliases(final ForwardQuery baseAllocation, final Query flowAllocation) {
			final AbstractBoomerangSolver<W> baseSolver = queryToSolvers.get(baseAllocation);
			final AbstractBoomerangSolver<W> flowSolver = queryToSolvers.get(flowAllocation);
			if (introducesLoop(baseAllocation, flowAllocation)) {
				return;
			}
			assert !flowSolver.getSuccsOf(getStmt()).isEmpty();
			baseSolver
					.registerStatementFieldTransitionListener(new StatementBasedFieldTransitionListener<W>(getStmt()) {

						@Override
						public void onAddedTransition(Transition<Field, INode<Node<Statement, Val>>> t) {
							final INode<Node<Statement, Val>> aliasedVariableAtStmt = t.getStart();
							if (!(aliasedVariableAtStmt instanceof GeneratedState)) {
								Val alias = aliasedVariableAtStmt.fact().fact();
								final WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut = baseSolver
										.getFieldAutomaton();
								aut.registerListener(new ImportToSolver(t.getTarget(), baseAllocation, flowAllocation));

								for (final Statement succOfWrite : flowSolver.getSuccsOf(getStmt())) {
									Node<Statement, Val> aliasedVarAtSucc = new Node<Statement, Val>(succOfWrite,
											alias);
									insertTransition(flowSolver.getFieldAutomaton(),
											new Transition<Field, INode<Node<Statement, Val>>>(
													new AllocNode<Node<Statement, Val>>(
																	baseAllocation.asNode()),
													Field.epsilon(), new SingleNode<Node<Statement, Val>>(
															new Node<Statement, Val>(succOfWrite, getBaseVar()))));
									insertTransition(flowSolver.getFieldAutomaton(),
											new Transition<Field, INode<Node<Statement, Val>>>(
													new SingleNode<Node<Statement, Val>>(aliasedVarAtSucc),
													t.getLabel(), t.getTarget()));
									Node<Statement, Val> rightOpNode = new Node<Statement, Val>(getStmt(),
											getStoredVar());
									flowSolver.setFieldContextReachable(aliasedVarAtSucc);
									flowSolver.addNormalCallFlow(rightOpNode, aliasedVarAtSucc);
								}

							}
						}
					});
		}

	}

	class ImportToSolver extends WPAStateListener<Field, INode<Node<Statement, Val>>, W> {
		private Query flowSolver;
		private Query baseSolver;

		public ImportToSolver(INode<Node<Statement, Val>> state, Query baseSolver,
				Query flowSolver) {
			super(state);
			this.baseSolver = baseSolver;
			this.flowSolver = flowSolver;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = super.hashCode();
			result = prime * result + getOuterType().hashCode();
			result = prime * result + ((flowSolver == null) ? 0 : flowSolver.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (!super.equals(obj))
				return false;
			if (getClass() != obj.getClass())
				return false;
			ImportToSolver other = (ImportToSolver) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (flowSolver == null) {
				if (other.flowSolver != null)
					return false;
			} else if (!flowSolver.equals(other.flowSolver))
				return false;
			return true;
		}

		@Override
		public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {

			insertTransition(queryToSolvers.get(flowSolver).getFieldAutomaton(), t);
			if(!t.getLabel().equals(Field.empty())) {
				queryToSolvers.get(baseSolver).getFieldAutomaton().registerListener(new ImportToSolver(t.getTarget(), baseSolver, flowSolver));
			}
		}

		@Override
		public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
				WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
		}

		private WeightedBoomerang getOuterType() {
			return WeightedBoomerang.this;
		}
	}


	private boolean addAllocationType(RefType type) {
		if (allocatedTypes.add(type)) {
			if(type.getSootClass().isInterface())
				return true;
			List<SootClass> classes = Scene.v().getActiveHierarchy().getSuperclassesOfIncluding(type.getSootClass());
			for (SootClass c : classes) {
				for (SootMethod m : c.getMethods()) {
					addTypeReachable(m);
				}
			}
			return true;
		}
		return false;
	}

	private void addTypeReachable(SootMethod m) {
		if (typeReachable.add(m)) {
			if (flowReachable.contains(m) || m.isStaticInitializer() || !options.onTheFlyCallGraph()) {
				addReachable(m);
			}
		}
	}

	private void addFlowReachable(SootMethod m) {
		if(flowReachable.add(m)){
			if(typeReachable.contains(m) || m.isStatic() || !options.onTheFlyCallGraph()){
				addReachable(m);
			}
		}
	}

	protected void addReachable(SootMethod m) {
		if (reachableMethods.add(m)) {
			Collection<Runnable> collection = queuedReachableMethod.get(m);
			for (Runnable runnable : collection) {
				runnable.run();
			}
			for (ReachableMethodListener<W> l : Lists.newArrayList(reachableMethodListeners)) {
				l.reachable(m);
			}
		}
	}

	public abstract BiDiInterproceduralCFG<Unit, SootMethod> icfg();

	protected abstract WeightFunctions<Statement, Val, Field, W> getForwardFieldWeights();

	protected abstract WeightFunctions<Statement, Val, Field, W> getBackwardFieldWeights();

	protected abstract WeightFunctions<Statement, Val, Statement, W> getBackwardCallWeights();

	protected abstract WeightFunctions<Statement, Val, Statement, W> getForwardCallWeights(ForwardQuery sourceQuery);

	public Collection<? extends Node<Statement, Val>> getForwardReachableStates() {
		Set<Node<Statement, Val>> res = Sets.newHashSet();
		for (Query q : queryToSolvers.keySet()) {
			if (q instanceof ForwardQuery)
				res.addAll(queryToSolvers.getOrCreate(q).getReachedStates());
		}
		return res;
	}

	public DefaultValueMap<Query, AbstractBoomerangSolver<W>> getSolvers() {
		return queryToSolvers;
	}

	public Table<Statement,Val, W> getResults(){
		final Table<Statement,Val, W> results = HashBasedTable.create();
		for (final Entry<Query, AbstractBoomerangSolver<W>> fw : queryToSolvers.entrySet()) {
			if(fw.getKey() instanceof ForwardQuery){
				fw.getValue().getFieldAutomaton().registerListener(new WPAStateListener<Field, INode<Node<Statement, Val>>, W>(fw.getValue().getFieldAutomaton().getInitialState()) {
					
					@Override
					public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
							WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
					}
					
					@Override
					public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
							WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
						if(t.getLabel().equals(Field.empty())){
							results.put(fw.getKey().stmt(),fw.getKey().var(), w);
						}
					}
				});
			}
		}
		return results;
	}

	public Table<Statement,Val, W> getResults(final Query query){
		final Table<Statement,Val, W> results = HashBasedTable.create();
		if(query instanceof ForwardQuery){
			WeightedPAutomaton<Statement, INode<Val>, W> fieldAut = queryToSolvers.getOrCreate(query).getCallAutomaton();
			for(Entry<Transition<Statement, INode<Val>>, W> e : fieldAut.getTransitionsToFinalWeights().entrySet()){
				Transition<Statement, INode<Val>> t = e.getKey();
				W w = e.getValue();
				if(t.getLabel().getUnit().isPresent())
					results.put(t.getLabel(),t.getStart().fact(),w);
			}
			return results;
		}
		for (final Entry<Query, AbstractBoomerangSolver<W>> fw : queryToSolvers.entrySet()) {
			if(fw.getKey() instanceof ForwardQuery){
				fw.getValue().getFieldAutomaton().registerListener(new WPAStateListener<Field, INode<Node<Statement, Val>>, W>(fw.getValue().getFieldAutomaton().getInitialState()) {
					
					@Override
					public void onOutTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
							WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
					}
					
					@Override
					public void onInTransitionAdded(Transition<Field, INode<Node<Statement, Val>>> t, W w,
							WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> weightedPAutomaton) {
						if(t.getLabel().equals(Field.empty()) && t.getStart().fact().equals(query.asNode())){
							results.put(fw.getKey().stmt(),fw.getKey().var(), w);
						}
					}
				});
			}
		}
		return results;
	}
	
	public Table<Statement, Val, W>  getObjectDestructingStatements(
			ForwardQuery seed) {
		AbstractBoomerangSolver<W> solver = queryToSolvers.get(seed);
		if(solver == null)
			return HashBasedTable.create();
		Table<Statement, Val, W> res = getResults(seed);
		Set<SootMethod> visitedMethods = Sets.newHashSet();
		for(Statement s : res.rowKeySet()){
			visitedMethods.add(s.getMethod());
		}
		ForwardBoomerangSolver<W> flowFunction = createForwardSolver(seed);
		Table<Statement, Val, W> destructingStatement = HashBasedTable.create();
		for(SootMethod flowReaches : visitedMethods){
			for(Unit ep : icfg().getEndPointsOf(flowReaches)){
				Statement exitStmt = new Statement((Stmt) ep, flowReaches);
				Set<State> escapes = Sets.newHashSet();
				for(Unit callSite : icfg().getCallersOf(flowReaches)){
					SootMethod callee = icfg().getMethodOf(callSite);
					if(flowReachable.contains(callee)){
						for(Entry<Val, W> valAndW : res.row(exitStmt).entrySet()){
							for(Unit retSite : icfg().getSuccsOf(callSite)){
								escapes.addAll(flowFunction.computeReturnFlow(flowReaches, (Stmt) ep, valAndW.getKey(), (Stmt) callSite, (Stmt) retSite));
							}
						}
					}
				}
				if(escapes.isEmpty()){
					Map<Val, W> row = res.row(exitStmt);
					for(Entry<Val, W> e : row.entrySet()){
						destructingStatement.put(exitStmt, e.getKey(), e.getValue());
					}
				}
			}
		}
		return destructingStatement;
	}
	
	public abstract Debugger<W> createDebugger();

	public void debugOutput() {
		// for (Query q : queryToSolvers.keySet()) {
		// System.out.println(q +" Nodes: " +
		// queryToSolvers.getOrCreate(q).getReachedStates().size());
		// System.out.println(q +" Field Aut: " +
		// queryToSolvers.getOrCreate(q).getFieldAutomaton().getTransitions().size());
		// System.out.println(q +" Field Aut (failed Additions): " +
		// queryToSolvers.getOrCreate(q).getFieldAutomaton().failedAdditions);
		// System.out.println(q +" Field Aut (failed Direct Additions): " +
		// queryToSolvers.getOrCreate(q).getFieldAutomaton().failedDirectAdditions);
		// System.out.println(q +" Call Aut: " +
		// queryToSolvers.getOrCreate(q).getCallAutomaton().getTransitions().size());
		// System.out.println(q +" Call Aut (failed Additions): " +
		// queryToSolvers.getOrCreate(q).getCallAutomaton().failedAdditions);
		// }
		Debugger<W> debugger = getOrCreateDebugger();
		for (Query q : queryToSolvers.keySet()) {
			debugger.reachableNodes(q, queryToSolvers.getOrCreate(q).getTransitionsToFinalWeights());
		}
		if (!DEBUG)
			return;

		int totalRules = 0;
		for (Query q : queryToSolvers.keySet()) {
			totalRules += queryToSolvers.getOrCreate(q).getNumberOfRules();
		}
		System.out.println("Total number of rules: " + totalRules);
		for (Query q : queryToSolvers.keySet()) {
			debugger.reachableNodes(q, queryToSolvers.getOrCreate(q).getTransitionsToFinalWeights());
			debugger.reachableCallNodes(q, queryToSolvers.getOrCreate(q).getReachedStates());
			debugger.reachableFieldNodes(q, queryToSolvers.getOrCreate(q).getReachedStates());

			System.out.println("========================");
			System.out.println(q);
			System.out.println("========================");
			queryToSolvers.getOrCreate(q).debugOutput();
//			for (FieldReadPOI p : fieldReads.values()) {
//				queryToSolvers.getOrCreate(q).debugFieldAutomaton(p.getStmt());
//				for (Statement succ : queryToSolvers.getOrCreate(q).getSuccsOf(p.getStmt())) {
//					queryToSolvers.getOrCreate(q).debugFieldAutomaton(succ);
//				}
//			}
//			for (FieldWritePOI p : fieldWrites.values()) {
//				queryToSolvers.getOrCreate(q).debugFieldAutomaton(p.getStmt());
//				for (Statement succ : queryToSolvers.getOrCreate(q).getSuccsOf(p.getStmt())) {
//					queryToSolvers.getOrCreate(q).debugFieldAutomaton(succ);
//				}
//			}
		}
	}
	
	public Debugger<W> getOrCreateDebugger() {
		if(this.debugger == null)
			this.debugger = createDebugger();
		return debugger;
	}

	public BoomerangStats<W> getStats() {
		return stats;
	}
}
