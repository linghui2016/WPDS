package tests;

import static org.junit.Assert.assertEquals;
import static tests.TestHelper.ACC;
import static tests.TestHelper.t;
import static tests.TestHelper.waccepts;
import static tests.TestHelper.wnormal;
import static tests.TestHelper.wpop;
import static tests.TestHelper.wpush;

import org.junit.Before;
import org.junit.Test;

import tests.TestHelper.Abstraction;
import tests.TestHelper.StackSymbol;
import wpds.impl.WeightedPAutomaton;
import wpds.impl.WeightedPushdownSystem;

public class WPDSPreStarTests {
  private WeightedPushdownSystem<StackSymbol, Abstraction, NumWeight<StackSymbol>> pds;

  @Before
  public void init() {
    pds = new WeightedPushdownSystem<StackSymbol, Abstraction, NumWeight<StackSymbol>>() {

      @Override
      public NumWeight<StackSymbol> getZero() {
        return NumWeight.zero();
      }

      @Override
      public NumWeight<StackSymbol> getOne() {
        return NumWeight.one();
      }
    };
  }

  @Test
  public void simple() {
    pds.addRule(wnormal(1, "a", 2, "b", w(2)));
    pds.addRule(wnormal(2, "b", 3, "c", w(3)));
    WeightedPAutomaton<StackSymbol, Abstraction, NumWeight<StackSymbol>> fa =
        waccepts(3, "c", w(0));
    pds.prestar(fa);
    assertEquals(3, fa.getTransitions().size());
    assertEquals(4, fa.getStates().size());
    assertEquals(w(5), fa.getWeightFor(t(1, "a", ACC)));
  }

  @Test
  public void branch() {
    pds.addRule(wnormal(1, "a", 1, "b", w(2)));
    pds.addRule(wnormal(1, "b", 1, "c", w(4)));
    pds.addRule(wnormal(1, "a", 1, "d", w(3)));
    pds.addRule(wnormal(1, "d", 1, "c", w(3)));
    WeightedPAutomaton<StackSymbol, Abstraction, NumWeight<StackSymbol>> fa =
        waccepts(1, "c", w(0));
    pds.prestar(fa);
    assertEquals(fa.getWeightFor(t(1, "a", ACC)), w(6));
    assertEquals(fa.getWeightFor(t(1, "b", ACC)), w(4));
    assertEquals(fa.getWeightFor(t(1, "d", ACC)), w(3));
  }


  @Test
  public void push1() {
    pds.addRule(wnormal(1, "a", 1, "b", w(2)));
    pds.addRule(wpush(1, "b", 1, "c", "d", w(3)));
    pds.addRule(wnormal(1, "c", 1, "e", w(1)));
    pds.addRule(wpop(1, "e", 1, w(5)));
    pds.addRule(wnormal(1, "d", 1, "f", w(6)));
    WeightedPAutomaton<StackSymbol, Abstraction, NumWeight<StackSymbol>> fa =
        waccepts(1, "f", w(0));
    pds.prestar(fa);
    System.out.println(fa);
    assertEquals(fa.getWeightFor(t(1, "a", ACC)), w(17));
    assertEquals(fa.getWeightFor(t(1, "b", ACC)), w(15));
    assertEquals(fa.getWeightFor(t(1, "c", 1)), w(6));
  }

  private static NumWeight<StackSymbol> w(int i) {
    return new NumWeight<>(i);
  }
}
