package org.rpgengine.inference.admission;

import java.util.List;

/**
 * Admission of one or more requests onto a lease.
 *
 * @param lease the lease after the admission, with every member
 * @param admitted the requests admitted by this grant, in service order
 * @param created true when this grant created the lease, so its model must be made resident; false
 *        when the requests joined a lease whose model is already resident
 */
public record Grant(Lease lease, List<AdmissionRequest> admitted, boolean created) {
    /** Copy the admitted list. */
    public Grant {
        admitted = List.copyOf(admitted);
    }
}
