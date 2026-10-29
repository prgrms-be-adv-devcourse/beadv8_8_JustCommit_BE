/**
 * Cross-cutting infrastructure module.
 *
 * <p>Keep business logic in its owning domain module. Module boundaries must remain suitable for a
 * future MSA split: use public APIs, Ports, or DTOs between modules, never direct repository or JPA
 * entity access. Cross-module foreign IDs are allowed without strong JPA entity relationships.</p>
 */
@org.springframework.modulith.NamedInterface("exception")
package com.justcommit.backend.common.exception;

