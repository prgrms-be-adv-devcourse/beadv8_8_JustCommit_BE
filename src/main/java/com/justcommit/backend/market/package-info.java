/**
 * Marketplace transaction module boundary.
 *
 * <p>This module owns the transaction lifecycle: cart, checkout, order, shipment, and refund.
 * Shipment and refund are internal responsibilities, not separate Spring Modulith modules. Other
 * modules must use a public API, Port, or DTO and must not access this module's repositories or JPA
 * entities directly. Cross-module foreign IDs are allowed, but JPA entity relationships across
 * modules are not.</p>
 */
@org.springframework.modulith.ApplicationModule(displayName = "Market")
package com.justcommit.backend.market;
