package analysis;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import wpds.impl.PAutomaton;
import wpds.impl.PushdownSystem;
import wpds.impl.Transition;
import wpds.impl.UNormalRule;
import wpds.impl.UPopRule;
import wpds.impl.UPushRule;
import wpds.interfaces.Location;
import wpds.interfaces.State;

public abstract class Solver<Stmt extends Location, Fact, Field extends Location> {

	public enum PDSSystem {
		FIELDS, METHODS
	}

	private PushdownSystem<Stmt, INode<Fact>> callingPDS = new PushdownSystem<Stmt, INode<Fact>>() {
	};
	private PushdownSystem<Field, INode<StmtWithFact>> fieldPDS = new PushdownSystem<Field, INode<StmtWithFact>>() {
	};
	private PAutomaton<Field, INode<StmtWithFact>> fieldPA;
	private PAutomaton<Stmt, INode<Fact>> callPA;
	private LinkedList<Node<Stmt, Fact>> worklist = Lists.newLinkedList();
	private Node<Stmt, Fact> seed;
	private Set<Node<Stmt, Fact>> reachedStates = Sets.newHashSet();
	private Set<Stmt> callSuccessors = Sets.newHashSet();
	private Set<Field> fieldReturnSuccessors = Sets.newHashSet();
	private Set<Node<Stmt, Fact>> callingContextReachable = Sets.newHashSet();
	private Set<Node<Stmt, Fact>> fieldContextReachable = Sets.newHashSet();
	private Multimap<Node<Stmt, Fact>, AvailableListener> onFieldContextReachable = HashMultimap.create();
	private Multimap<Node<Stmt, Fact>, AvailableListener> onCallingContextReachable = HashMultimap.create();
	private Multimap<Node<Stmt, Fact>, Node<Stmt, Fact>> solvedEdges = HashMultimap.create();

	public void solve(Node<Stmt, Fact> curr) {
		this.seed = curr;
		worklist.add(curr);
		awaitEmptyWorklist();
	}

	private void awaitEmptyWorklist() {
		while (!worklist.isEmpty()) {
			Node<Stmt, Fact> curr = worklist.poll();
			reachedStates.add(curr);

			Collection<State> successors = computeSuccessor(curr);
			for (State s : successors) {
				if (s instanceof Node) {
					Node<Stmt, Fact> succ = (Node<Stmt, Fact>) s;
					if (!addEdge(curr, succ))
						continue;

					if (succ instanceof PushNode) {
						PushNode<Stmt, Fact, Location> pushNode = (PushNode<Stmt, Fact, Location>) succ;
						PDSSystem system = pushNode.system();
						Location location = pushNode.location();
						processPush(curr, location, pushNode, system);
					} else {
						processNormal(curr, succ);
						setCallingContextReachable(new QueuedNode(succ.stmt(), succ.fact()));
						setFieldContextReachable(new QueuedNode(succ.stmt(), succ.fact()));
					}
					addToWorklist(succ);
				} else if (s instanceof PopNode) {
					PopNode<Fact> popNode = (PopNode<Fact>) s;
					PDSSystem system = popNode.system();
					Fact location = popNode.location();
					processPop(curr, location, system);
				}
			}
		}
	}

	private void addToWorklist(Node<Stmt, Fact> curr) {
		worklist.add(new Node<Stmt, Fact>(curr.stmt(), curr.fact()));
	}

	private void processNormal(Node<Stmt, Fact> curr, Node<Stmt, Fact> succ) {
		addNormalFieldFlow(curr, succ);
		addNormalCallFlow(curr, succ);
	}

	private void addNormalCallFlow(Node<Stmt, Fact> curr, Node<Stmt, Fact> succ) {
		callingPDS.addRule(
				new UNormalRule<Stmt, INode<Fact>>(wrap(curr.fact()), curr.stmt(), wrap(succ.fact()), succ.stmt()));
	}

	private void addNormalFieldFlow(Node<Stmt, Fact> curr, Node<Stmt, Fact> succ) {
		setFieldContextReachable(new QueuedNode(succ));
		fieldPDS.addRule(new UNormalRule<Field, INode<StmtWithFact>>(asFieldFact(curr), fieldWildCard(),
				asFieldFact(succ), fieldWildCard()));
	}

	public abstract Field fieldWildCard();

	private INode<StmtWithFact> asFieldFact(Node<Stmt, Fact> node) {
		return new SingleNode<StmtWithFact>(new StmtWithFact(node.stmt, node.fact()));
	}

	private void processPop(Node<Stmt, Fact> curr, Fact location, PDSSystem system) {
		if (system.equals(PDSSystem.FIELDS)) {
			System.out.println(curr + " HERE " + location);
			NodeWithLocation<Stmt, Fact, Field> node = (NodeWithLocation) location;
			fieldPDS.addRule(new UPopRule<Field, INode<StmtWithFact>>(asFieldFact(curr), node.location(),
					asFieldFact(node.fact())));
			setCallingContextReachable(new QueuedNode(node.fact()));
			checkFieldFeasibility(node.fact());
		} else if (system.equals(PDSSystem.METHODS)) {
			//
			callingPDS.addRule(new UPopRule<Stmt, INode<Fact>>(wrap(curr.fact()), curr.stmt(), wrap((Fact) location)));
			checkCallFeasibility(curr, location);
		}
	}

	private void processPush(Node<Stmt, Fact> curr, Location location, Node<Stmt, Fact> succ, PDSSystem system) {
		if (system.equals(PDSSystem.FIELDS)) {
			fieldPDS.addRule(new UPushRule<Field, INode<StmtWithFact>>(asFieldFact(curr), fieldWildCard(),
					asFieldFact(succ), fieldWildCard(), (Field) location));
			addNormalCallFlow(curr, succ);
			fieldReturnSuccessors.add((Field) location);
		} else if (system.equals(PDSSystem.METHODS)) {
			addNormalFieldFlow(curr, succ);
			callingPDS.addRule(new UPushRule<Stmt, INode<Fact>>(wrap(curr.fact()), curr.stmt(), wrap(succ.fact()),
					succ.stmt(), (Stmt) location));
			callSuccessors.add((Stmt) location);
		}
	}

	private boolean addEdge(Node<Stmt, Fact> curr, Node<Stmt, Fact> succ) {
		return solvedEdges.put(curr, succ);
	}

	private void checkFieldFeasibility(Node<Stmt, Fact> node) {
		System.out.println("CHECKING Field reachabilty for " + node);
		PAutomaton<Field, INode<StmtWithFact>> aut1 = getOrCreateFieldAutomaton();
		fieldPDS.poststar(aut1);
		for (INode<StmtWithFact> n : aut1.getStates()) {
			Solver<Stmt, Fact, Field>.StmtWithFact fact = n.fact();
			if (fact != null && fact.asNode().equals(node)) {
				setFieldContextReachable(new QueuedNode(fact.stmt(), fact.fact()));
			}
		}
	}

	private PAutomaton<Field, INode<StmtWithFact>> getOrCreateFieldAutomaton() {
		if (fieldPA == null) {
			fieldPA = new PAutomaton<Field, INode<StmtWithFact>>(asFieldFact(seed), asFieldFact(seed)) {
				@Override
				public INode<StmtWithFact> createState(INode<StmtWithFact> d, Field loc) {
					if (loc.equals(emptyField()))
						return d;
					return generateFieldState(d, loc);
				}

				@Override
				public Field epsilon() {
					return epsilonField();
				}
			};
			fieldPA.addTransition(
					new Transition<Field, INode<StmtWithFact>>(asFieldFact(seed), emptyField(), asFieldFact(seed)));
		}
		return fieldPA;
	}

	private void checkCallFeasibility(Node<Stmt, Fact> curr, Fact fact) {
		PAutomaton<Stmt, INode<Fact>> aut2 = getOrCreateCallAutomaton();
		callingPDS.poststar(aut2);
		for (Stmt retSite : callSuccessors) {
			Collection<Transition<Stmt, INode<Fact>>> transitionsOutOf = aut2
					.getTransitionsOutOf(new SingleNode<Fact>(fact));
			for (Transition<Stmt, INode<Fact>> t : transitionsOutOf) {
				if (t.getLabel().equals(retSite)) {
					addNormalFieldFlow(curr, new Node<Stmt, Fact>(retSite, fact));
					setCallingContextReachable(new QueuedNode(retSite, fact));
				}
			}
		}
	}

	private PAutomaton<Stmt, INode<Fact>> getOrCreateCallAutomaton() {
		if (callPA == null) {
			callPA = new PAutomaton<Stmt, INode<Fact>>(wrap(seed.variable), wrap(seed.variable)) {
				@Override
				public INode<Fact> createState(INode<Fact> d, Stmt loc) {
					return generateState(d, loc);
				}

				@Override
				public Stmt epsilon() {
					return epsilonCallSite();
				}
			};
			callPA.addTransition(
					new Transition<Stmt, INode<Fact>>(wrap(seed.variable), seed.stmt(), wrap(seed.variable)));
		}
		return callPA;
	}

	private class QueuedNode extends Node<Stmt, Fact> implements AvailableListener {
		public QueuedNode(Stmt stmt, Fact variable) {
			super(stmt, variable);
		}

		public QueuedNode(Node<Stmt, Fact> node) {
			super(node.stmt(), node.fact());
		}

		@Override
		public void available() {
			addToWorklist(this);
		}

		public Node<Stmt, Fact> asNode() {
			return new Node<Stmt, Fact>(stmt(), fact());
		}
	}

	private void setCallingContextReachable(QueuedNode queuedNode) {
		Node<Stmt, Fact> node = queuedNode.asNode();
		callingContextReachable.add(node);
		System.out.println("Set Calling Context Reachable " + node);
		Collection<AvailableListener> listeners = onCallingContextReachable.get(node);
		for (AvailableListener l : listeners) {
			l.available();
		}
		if (fieldContextReachable.contains(node)) {
			queuedNode.available();
		} else {
			onCallingContextReachable.put(node, queuedNode);
		}
	}

	private void setFieldContextReachable(QueuedNode queuedNode) {
		Node<Stmt, Fact> node = queuedNode.asNode();
		fieldContextReachable.add(node);
		System.out.println("Set Field Context Reachable " + node);
		Collection<AvailableListener> listeners = onFieldContextReachable.get(node);
		for (AvailableListener l : listeners) {
			l.available();
		}
		if (callingContextReachable.contains(node)) {
			queuedNode.available();
		} else {
			onFieldContextReachable.put(node, queuedNode);
		}
	}

	private INode<Fact> wrap(Fact variable) {
		return new SingleNode<Fact>(variable);
	}

	Map<Entry<INode<Fact>, Stmt>, INode<Fact>> generatedCallState = Maps.newHashMap();

	protected INode<Fact> generateState(final INode<Fact> d, final Stmt loc) {
		Entry<INode<Fact>, Stmt> e = new AbstractMap.SimpleEntry<>(d, loc);
		if (!generatedCallState.containsKey(e)) {
			generatedCallState.put(e, new INode<Fact>() {
				@Override
				public Fact fact() {
					throw new RuntimeException("System internal state");
				}

				@Override
				public String toString() {
					return d + " " + loc;
				}
			});
		}
		return generatedCallState.get(e);
	}

	Map<Entry<INode<StmtWithFact>, Field>, INode<StmtWithFact>> generatedFieldState = Maps.newHashMap();

	protected INode<StmtWithFact> generateFieldState(final INode<StmtWithFact> d, final Field loc) {
		Entry<INode<StmtWithFact>, Field> e = new AbstractMap.SimpleEntry<>(d, loc);
		if (!generatedFieldState.containsKey(e)) {
			generatedFieldState.put(e, new INode<StmtWithFact>() {
				@Override
				public StmtWithFact fact() {
					return null;
					// throw new RuntimeException("System internal state");
				}

				@Override
				public String toString() {
					return d + " " + loc;
				}
			});
		}
		return generatedFieldState.get(e);
	}

	public abstract Collection<State> computeSuccessor(Node<Stmt, Fact> node);

	public abstract Field epsilonField();

	public abstract Field emptyField();

	public abstract Stmt epsilonCallSite();

	public abstract Stmt emptyCallSite();

	public Set<Node<Stmt, Fact>> getReachedStates() {
		return Sets.newHashSet(reachedStates);
	}

	private interface AvailableListener {
		void available();
	}

	private class StmtWithFact extends Node<Stmt, Fact> {

		public StmtWithFact(Stmt stmt, Fact variable) {
			super(stmt, variable);
		}

		public Node<Stmt, Fact> asNode() {
			return new Node<Stmt, Fact>(stmt(), fact());
		}
	}
}
