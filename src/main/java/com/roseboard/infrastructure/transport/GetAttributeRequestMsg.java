package com.roseboard.infrastructure.transport;

import java.util.List;

public record GetAttributeRequestMsg(int requestId, List<String> clientAttributeNames, List<String> sharedAttributeNames) {
}
