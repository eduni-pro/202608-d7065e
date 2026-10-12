package se.ltu.d7065e.shared.event;

import se.ltu.d7065e.ventilation.entity.ObservedAt;

/**
 * The payload on every transport this project owns (ADR-0006): the domain event is
 * serialised directly, with no parallel DTO hierarchy.
 */
public sealed interface DomainEvent
        permits ReadingObserved, FanPowerObserved, CommandIssued, FanSpeedCommanded,
                ResidualComputed, FaultStateChanged, StrategyChanged, ServiceReported {

    ObservedAt at();
}
