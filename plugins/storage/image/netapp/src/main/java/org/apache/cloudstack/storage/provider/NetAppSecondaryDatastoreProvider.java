package org.apache.cloudstack.storage.provider;

import com.cloud.storage.ScopeType;
import com.cloud.utils.component.ComponentContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.HypervisorHostListener;
import org.apache.cloudstack.engine.subsystem.api.storage.ImageStoreProvider;
import org.apache.cloudstack.storage.driver.NetAppSecondaryDatastoreDriver;
import org.apache.cloudstack.storage.image.datastore.ImageStoreProviderManager;
import org.apache.cloudstack.storage.lifecycle.NetAppSecondaryDatastoreLifecycle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class NetAppSecondaryDatastoreProvider implements ImageStoreProvider {

    private static final Logger s_logger = (Logger)LogManager.getLogger(NetAppSecondaryDatastoreProvider.class);
    @Inject
    ImageStoreProviderManager storeMgr;
    private NetAppSecondaryDatastoreDriver secondaryDatastoreDriver;
    private NetAppSecondaryDatastoreLifecycle secondaryDatastoreLifecycle;

    public NetAppSecondaryDatastoreProvider() {
        s_logger.info("NetAppSecondaryDatastoreProvider initialized");
    }
    @Override
    public DataStoreLifeCycle getDataStoreLifeCycle() {
        return secondaryDatastoreLifecycle;
    }

    @Override
    public DataStoreDriver getDataStoreDriver() {
        return secondaryDatastoreDriver;
    }

    @Override
    public HypervisorHostListener getHostListener() {
        return null;
    }

    @Override
    public String getName() {
        return "NetApp";
    }

    @Override
    public boolean configure(Map<String, Object> params) {
        secondaryDatastoreLifecycle = ComponentContext.inject(NetAppSecondaryDatastoreLifecycle.class);
        secondaryDatastoreDriver = ComponentContext.inject(NetAppSecondaryDatastoreDriver.class);
        storeMgr.registerDriver(this.getName(), secondaryDatastoreDriver);
        return true;
    }

    @Override
    public Set<DataStoreProviderType> getTypes() {
        Set<DataStoreProviderType> types = new HashSet<DataStoreProviderType>();
        types.add(DataStoreProviderType.IMAGE);
        return types;
    }

    @Override
    public boolean isScopeSupported(ScopeType scope) {
        if (scope == ScopeType.REGION)
            return true;
        return false;
    }

    @Override
    public boolean needDownloadSysTemplate() {
        return true;
    }
}