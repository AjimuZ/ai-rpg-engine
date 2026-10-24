/**
 * Campaign aggregate, its canonical world counters and the append-only domain event log.
 * Presentation preferences live in the presentation package so they never share a row
 * with world counters. Atomic creation validates sealed pack choices before writing
 * initial state; its protected HTTP adapter returns committed identities or safe error codes.
 * Independent read-only snapshots authorize owner/adventure/campaign and expose only the
 * creation-bound player and explicitly audience-bound knowledge through detached DTOs.
 */
package org.rpgengine.campaign;
