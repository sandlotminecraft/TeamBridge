package com.sandlotminecraft.teambridge;

import java.util.Objects;
import java.util.UUID;

/** A party id and the name it had when it was read. The name may be null (a nameless party). */
public record TeamRef(UUID id, String name) {
    public TeamRef {
        Objects.requireNonNull(id, "id");
    }
}
