/**
 * Defines immutable observation policy/status, interaction identity, evidence, and
 * forwarding-decision and per-interaction permit contracts.
 *
 * <p>These contracts are independent of journal storage. A journal records observation values but
 * does not own their schemas, session identities, or stream ordinals. Connection-scoped
 * publication capabilities belong to the execution extension SPI. A recorded interaction carries
 * the already captured evidence snapshot to the decision boundary, and a forwarding permit reports
 * authorization plus the eventual write/flush outcome without exposing transport-owned bytes or
 * resources.
 */
package io.github.jacekkardys.systemproof.observation;
