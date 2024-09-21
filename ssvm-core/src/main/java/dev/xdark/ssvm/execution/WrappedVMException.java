package dev.xdark.ssvm.execution;

/**
 * A wrapped {@link VMException} that will print much prettier and readable exception
 */
public class WrappedVMException extends Throwable {
  private final String className;

  public WrappedVMException(String className, String message) {
    super(message);
    this.className = className;
  }

  @Override
  public String toString() {
    String s = this.className; // Set correct class name
    String message = getLocalizedMessage();
    return (message != null) ? (s + ": " + message) : s;
  }
}
