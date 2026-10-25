/**
 * Adventure-owned immutable content revisions. Bounded filesystem/archive staging,
 * structural validation and semantic compilation turn untrusted pack bytes into sealed
 * database records; invalid inputs fail before admission. Optional private filesystem
 * projections copy verified originals after commit or at startup. Projection failure
 * preserves canonical records and fails closed until the private storage is repaired.
 */
package org.rpgengine.content;
