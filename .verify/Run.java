import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.*;

public class Run {
  public static void main(String[] args) {
    LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
        .selectors(java.util.Arrays.stream(args).map(s -> selectClass(s)).toList())
        .build();
    Launcher launcher = LauncherFactory.create();
    SummaryGeneratingListener listener = new SummaryGeneratingListener();
    launcher.execute(request, listener);
    listener.getSummary().printTo(new java.io.PrintWriter(System.out));
    listener.getSummary().printFailuresTo(new java.io.PrintWriter(System.out), 50);
    System.exit(listener.getSummary().getTotalFailureCount() > 0 ? 1 : 0);
  }
}
