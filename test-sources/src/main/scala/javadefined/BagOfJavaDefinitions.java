package javadefined;

public class BagOfJavaDefinitions {
  public int x;

  public BagOfJavaDefinitions(int x) {
    this.x = x;
  }

  public void printX() {
    System.out.println(x);
  }

  public int[] wrapXArray() {
    return new int[] { x };
  }

  public javadefined.JavaDefined[] arrIdentity(javadefined.JavaDefined[] arr) {
    return arr;
  }
}
