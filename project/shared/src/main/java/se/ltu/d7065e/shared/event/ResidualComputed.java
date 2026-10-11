package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.Residual;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** Published on {@code residuals/{zone}} every monitor cycle, fault or not. */
public record ResidualComputed(
        ZoneId zone, Residual residual, CusumStatistic cusum, ObservedAt at, CommandId commandId)
        implements DomainEvent {

    public ResidualComputed {
        if (zone == null || residual == null || cusum == null || at == null) {
            throw new IllegalArgumentException("residual fields must not be null");
        }
    }
}
