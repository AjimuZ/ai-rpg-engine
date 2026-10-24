/**
 * Campaign aggregate, its canonical world counters and the append-only domain event log.
 * Presentation preferences live in the presentation package so they never share a row
 * with world counters. Atomic creation validates sealed pack choices before writing
 * initial state; its protected HTTP adapter returns committed identities or safe error codes.
 */
package org.rpgengine.campaign;
