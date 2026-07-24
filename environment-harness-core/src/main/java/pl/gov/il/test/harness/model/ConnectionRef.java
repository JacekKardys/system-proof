package pl.gov.il.test.harness.model;

/** Non-generic environment registry view of one directional logical connection. */
public interface ConnectionRef {
    String id();

    PortRef from();

    PortRef to();
}
