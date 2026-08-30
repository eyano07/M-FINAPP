package com.mbsc.finapp.dto.parametrage;

import com.mbsc.finapp.domain.enums.NiveauPermission;
import jakarta.validation.constraints.NotNull;

public record NiveauPermissionRequest(@NotNull NiveauPermission niveau) {}
