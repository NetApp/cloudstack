package org.apache.cloudstack.storage.provider;

import com.cloud.exception.StorageConflictException;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;

public class OntapHostListener implements HypervisorHostListener {
    @Override
    public boolean hostAdded(long hostId) {
        return false;
    }

    @Override
    public boolean hostConnect(long hostId, long poolId) throws StorageConflictException {
        return false;
    }

    @Override
    public boolean hostDisconnected(long hostId, long poolId) {
        return false;
    }

    @Override
    public boolean hostAboutToBeRemoved(long hostId) {
        return false;
    }

    @Override
    public boolean hostRemoved(long hostId, long clusterId) {
        return false;
    }

    @Override
    public boolean hostEnabled(long hostId) {
        return false;
    }
}
