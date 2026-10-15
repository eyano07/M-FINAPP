package com.mbsc.finapp.dto.notes;

/**
 * Rattachement d'une note de frais a un service budgetaire ({@code serviceId} null : la note n'est plus rattachee a
 * aucun service).
 */
public record ModifierServiceRequest(Long serviceId) {}
