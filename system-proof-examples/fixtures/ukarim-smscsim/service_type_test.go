package main

import (
	"bytes"
	"testing"
)

func TestDeliverSmServiceTypeIsEmpty(t *testing.T) {
	const sender = "48111000111"
	pdu := deliverSmPDU(sender, "99001", []byte("fixture contract"), CODING_DEFAULT, 17, 0, nil)
	if len(pdu) <= 19 {
		t.Fatalf("deliver_sm PDU is too short: %d bytes", len(pdu))
	}

	body := pdu[16:]
	serviceTypeEnd := bytes.IndexByte(body, 0)
	if serviceTypeEnd < 0 {
		t.Fatal("deliver_sm service_type is not null-terminated")
	}
	if serviceTypeEnd != 0 {
		t.Fatalf("deliver_sm service_type must be empty, got %q", body[:serviceTypeEnd])
	}

	sourceAddressStart := serviceTypeEnd + 3
	sourceAddressEnd := bytes.IndexByte(body[sourceAddressStart:], 0)
	if sourceAddressEnd < 0 {
		t.Fatal("deliver_sm source_addr is not null-terminated")
	}
	if actual := string(body[sourceAddressStart : sourceAddressStart+sourceAddressEnd]); actual != sender {
		t.Fatalf("deliver_sm fields are misaligned: source_addr = %q, want %q", actual, sender)
	}
}
