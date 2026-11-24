package com.thekain.runtime.lab.network;

import java.io.IOException;

/** Fixed, redacted codes only: no URL, address, query, headers or chained client exception. */
public final class BrokerFailure extends IOException {
    public enum Code { INVALID_URL, OUT_OF_SCOPE, ADDRESS_DENIED, DNS_FAILED, DNS_BUSY, TIMEOUT,
        CANCELED, TLS_ERROR, NETWORK_LOST, HTTP_ERROR, REQUEST_LIMIT, RESPONSE_LIMIT, HEADER_LIMIT,
        UNSUPPORTED_ENCODING, UNSUPPORTED_FRAMING, BUSY }
    public final Code code;
    public final int httpStatus;
    public BrokerFailure(Code code) {this(code,0);}
    public BrokerFailure(Code code,int status) {super(code.name());this.code=code;httpStatus=status;}
}
