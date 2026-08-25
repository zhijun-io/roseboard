package com.roseboard.infrastructure.transport;

import java.util.List;

public record GetAttributeResponseMsg(int requestId,
                                      List<KeyValueEntry> clientAttributeList,
                                      List<KeyValueEntry> sharedAttributeList) {
}
