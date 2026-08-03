/**
 * Defines immutable interaction identity and evidence plus forwarding-decision contracts.
 *
 * <p>These contracts are independent of journal storage. A journal records observation values but
 * does not own their schemas, session identities, or stream ordinals. Connection-scoped
 * publication capabilities belong to the execution extension SPI.
 */
package io.github.jacekkardys.systemproof.observation;
