package pl.gov.il.test.harness.driver;

import pl.gov.il.test.harness.model.AbstractComponent;
import pl.gov.il.test.harness.model.RuntimeConfig;

/** Materializes one component instance in a selected runtime technology. */
@FunctionalInterface
public interface ComponentDriver<C extends RuntimeConfig, O> {
    ComponentRuntime<O> start(AbstractComponent<C, O> component, DriverContext context);
}
