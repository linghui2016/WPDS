package tests;

import static org.junit.Assert.assertTrue;
import static tests.TestHelper.ACC;
import static tests.TestHelper.a;
import static tests.TestHelper.accepts;
import static tests.TestHelper.normal;
import static tests.TestHelper.pop;
import static tests.TestHelper.push;
import static tests.TestHelper.s;
import static tests.TestHelper.t;

import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import tests.TestHelper.Abstraction;
import tests.TestHelper.StackSymbol;
import wpds.impl.PAutomaton;
import wpds.impl.PushdownSystem;
import wpds.impl.Transition;

public class PDSPoststarTests {

  private PushdownSystem<StackSymbol, Abstraction> pds;

  @Before
  public void init() {
    pds = new PushdownSystem<StackSymbol, Abstraction>() {};
  }

  @Test
  public void pushTest() {
    pds.addRule(normal(1, "a", 1, "b"));
    pds.addRule(push(1, "b", 1, "c", "d"));
    pds.addRule(pop(1, "c", 1));
    PAutomaton<StackSymbol, Abstraction> fa = accepts(1, "a");
    pds.poststar(fa);
    System.out.println(fa);
    System.out.println(fa.getTransitions());
    assertTrue(fa.getTransitions().contains(t(1, "d", ACC)));
  }

  @Test
  public void doublePushTest() {
    pds.addRule(normal(1, "a", 1, "b"));
    pds.addRule(normal(1, "b", 1, "c"));
    pds.addRule(push(1, "c", 1, "d", "e"));
    pds.addRule(push(1, "d", 1, "h", "i"));
    pds.addRule(pop(1, "h", 1));
    pds.addRule(pop(1, "d", 1));
    pds.addRule(normal(1, "e", 1, "k"));
    PAutomaton<StackSymbol, Abstraction> fa = accepts(1, "a");
    pds.poststar(fa);
    assertTrue(fa.getTransitions().contains(t(1, "k", ACC)));
    fa = accepts(1, "k");
    pds.prestar(fa);
    assertTrue(fa.getTransitions().contains(t(1, "a", ACC)));
  }


  @Test
  public void recPushTest() {
    pds.addRule(normal(1, "a", 1, "b"));
    pds.addRule(normal(1, "b", 1, "c"));
    pds.addRule(push(1, "c", 1, "d", "e"));
    pds.addRule(normal(1, "d", 1, "f"));
    pds.addRule(push(1, "f", 1, "d", "h"));
    pds.addRule(pop(1, "d", 1));
    pds.addRule(normal(1, "e", 1, "k"));
    PAutomaton<StackSymbol, Abstraction> fa = accepts(1, "a");
    pds.poststar(fa);
    assertTrue(fa.getTransitions().contains(t(1, "k", ACC)));
    assertTrue(fa.getTransitions().contains(t(1, "k", ACC)));
    assertTrue(fa.getTransitions().contains(t(1, fa.epsilon(), new Abstraction(a(1), s("d")))));
  }

  @Test
  public void recPushTestSimple() {
    pds.addRule(push(1, "a", 1, "d", "e"));
    pds.addRule(push(1, "d", 1, "d", "h"));
    pds.addRule(pop(1, "d", 1));
    pds.addRule(normal(1, "e", 1, "k"));
    PAutomaton<StackSymbol, Abstraction> fa = accepts(1, "a");
    pds.poststar(fa);
    Set<Transition<StackSymbol, Abstraction>> transitions = fa.getTransitions();
    transitions.remove(t(1, "e", ACC));
    transitions.remove(t(1, "a", ACC));
    transitions.remove(t(1, "k", ACC));
    transitions.remove(t(a(1, "d"), "e", ACC));
    transitions.remove(t(a(1, "d"), s("h"), a(1, "d")));
    transitions.remove(t(1, s("d"), a(1, "d")));
    transitions.remove(t(1, s("h"), a(1, "d")));
    transitions.remove(t(1, fa.epsilon(), a(1, "d")));
    assertTrue(transitions.isEmpty());
  }

  // Example taken from http://research.cs.wisc.edu/wpis/papers/fsttcs07.invited.pdf
  @Test
  public void paperEx() {
    pds.addRule(normal(1, "n1", 1, "n2"));
    pds.addRule(normal(1, "n1", 1, "n3"));
    pds.addRule(push(1, "n2", 1, "n7", "n4"));
    pds.addRule(push(1, "n3", 1, "n7", "n5"));
    pds.addRule(normal(1, "n4", 1, "n6"));
    pds.addRule(normal(1, "n5", 1, "n6"));
    pds.addRule(normal(1, "n7", 1, "n8"));
    pds.addRule(pop(1, "n8", 1));
    PAutomaton<StackSymbol, Abstraction> fa = accepts(1, "n1");
    pds.poststar(fa);
    Set<Transition<StackSymbol, Abstraction>> transitions = fa.getTransitions();
    transitions.remove(t(1, "n1", ACC));
    transitions.remove(t(1, "n2", ACC));
    transitions.remove(t(1, "n3", ACC));
    transitions.remove(t(1, "n4", ACC));
    transitions.remove(t(1, "n5", ACC));
    transitions.remove(t(1, "n6", ACC));
    transitions.remove(t(1, fa.epsilon(), a(1, "n7")));
    transitions.remove(t(1, "n7", a(1, "n7")));
    transitions.remove(t(1, "n8", a(1, "n7")));
    transitions.remove(t(a(1, "n7"), "n4", ACC));
    transitions.remove(t(a(1, "n7"), "n5", ACC));
    assertTrue(transitions.isEmpty());
  }
}
