package top.mossmoss.lessonisland;

import static org.junit.Assert.*;

import org.junit.Test;

public class LearningTest {
  @Test
  public void tolerantSpellingPreservesMeaning() {
    assertTrue(Learning.matches("  THANK   YOU! ", "Thank you."));
    assertTrue(Learning.matches("I wasn’t.", "I wasn't."));
    assertFalse(Learning.matches("wasnt", "wasn't"));
    assertFalse(Learning.matches("is this your bag", "is this your handbag"));
    assertFalse(Learning.matches("", " "));
  }

  @Test
  public void reviewIntervalsAreBounded() {
    long now = 123456L;
    assertEquals(now + Learning.DAY, Learning.nextReview(now, 0));
    assertEquals(now + 3 * Learning.DAY, Learning.nextReview(now, 1));
    assertEquals(now + 30 * Learning.DAY, Learning.nextReview(now, 100));
    assertEquals(now + Learning.DAY, Learning.nextReview(now, -2));
  }

  @Test
  public void accuracyIncludesWrongAttempts() {
    assertEquals(0, Learning.accuracy(0, 0));
    assertEquals(75, Learning.accuracy(3, 4));
    assertEquals(67, Learning.accuracy(2, 3));
  }

  @Test
  public void wordOrderKeepsDuplicateTokens() {
    assertEquals(5, Learning.tokens("Is this your your handbag?").size());
    assertEquals("your", Learning.tokens("Is this your your handbag?").get(3));
  }
}
