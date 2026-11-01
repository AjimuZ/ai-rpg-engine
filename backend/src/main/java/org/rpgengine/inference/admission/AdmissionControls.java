package org.rpgengine.inference.admission;

import java.util.Map;
import org.rpgengine.inference.ModelServerControl;

/**
 * What holds the models of each modality, as the {@link LeaseExecutor} receives it. A modality with no
 * entry cannot load. A wrapper rather than a bare map bean, so that injecting it never meets Spring's
 * collection handling.
 *
 * @param byModality the control of each modality; copied
 */
public record AdmissionControls(Map<Modality, ModelServerControl> byModality) {
    /** Copy the map. */
    public AdmissionControls {
        byModality = Map.copyOf(byModality);
    }
}
