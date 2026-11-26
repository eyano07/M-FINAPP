package com.mbsc.finapp.dto.push;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Jeton Firebase Cloud Messaging du téléphone. */
public record AppareilPushRequest(@NotBlank @Size(max = 512) String jeton) {
}
