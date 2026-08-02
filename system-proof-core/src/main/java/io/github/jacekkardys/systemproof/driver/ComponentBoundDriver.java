package io.github.jacekkardys.systemproof.driver;

import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.RuntimeConfig;

/**
 * Driver SPI for runtime technologies that bind a driver to one concrete component class.
 */
public interface ComponentBoundDriver<
    C extends RuntimeConfig,
    O,
    T extends AbstractComponent<C, O>
> extends ComponentDriver<C, O> {}
