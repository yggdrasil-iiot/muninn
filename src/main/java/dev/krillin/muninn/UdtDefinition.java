package dev.krillin.muninn;

import java.util.List;

public record UdtDefinition(
        String templateRef,
        String version,
        List<Member> members,
        List<Param> params,
        String conformsTo
) {
}
