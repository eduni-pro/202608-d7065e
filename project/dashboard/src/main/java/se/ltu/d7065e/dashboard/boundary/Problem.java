package se.ltu.d7065e.dashboard.boundary;

import jakarta.json.Json;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The one shape every refusal from this container takes, {@code {status, detail}}. Shared by the
 * endpoints rather than copied into each, because the error body is as much of the HTTP contract as
 * the success body and two copies would drift the moment one of them gained a field.
 */
interface Problem {

    static Response of(Response.Status status, String detail) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Json.createObjectBuilder()
                        .add("status", status.getStatusCode())
                        .add("detail", detail)
                        .build().toString())
                .build();
    }
}
