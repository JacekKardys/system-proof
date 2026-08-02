/**
 * Public environment construction API.
 * EnvironmentBuilder, EnvironmentCreator, and ComponentPortFactory form the public boundary.
 * ComponentPortFactory belongs here because it creates ports and mutates component construction state;
 * no factory instance is retained at runtime. Reflection, materialization, annotation parsing, and
 * structural validation remain package-private implementation details.
 */
package io.github.jacekkardys.systemproof.construction;
