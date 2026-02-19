package org.apache.cloudstack.storage.driver;


import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DatadiskTO;
import com.cloud.host.Host;
import com.cloud.storage.Storage;
import com.cloud.storage.Upload;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.storage.image.ImageStoreDriver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class NetAppSecondaryDatastoreDriver implements ImageStoreDriver {

    @Override
    public Map<String, String> getCapabilities() {
        return null;
    }

    @Override
    public DataTO getTO(DataObject data) {
        return null;
    }

    @Override
    public DataStoreTO getStoreTO(DataStore store) {
        return null;
    }

    @Override
    public void createAsync(DataStore store, DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {

    }

    @Override
    public void deleteAsync(DataStore store, DataObject data, AsyncCompletionCallback<CommandResult> callback) {

    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, AsyncCompletionCallback<CopyCommandResult> callback) {

    }

    @Override
    public void copyAsync(DataObject srcData, DataObject destData, Host destHost, AsyncCompletionCallback<CopyCommandResult> callback) {

    }


    @Override
    public boolean canCopy(DataObject srcData, DataObject destData) {
        return false;
    }

    @Override
    public void resize(DataObject data, AsyncCompletionCallback<CreateCmdResult> callback) {

    }

    @Override
    public String createEntityExtractUrl(DataStore store, String installPath, Storage.ImageFormat format, DataObject dataObject) {
        return null;
    }

    @Override
    public void deleteEntityExtractUrl(DataStore store, String installPath, String url, Upload.Type entityType) {

    }

    @Override
    public List<DatadiskTO> getDataDiskTemplates(DataObject obj, String configurationId) {
        return null;
    }

    @Override
    public Void createDataDiskTemplateAsync(TemplateInfo dataDiskTemplate, String path, String diskId, boolean bootable, long fileSize, AsyncCompletionCallback<CreateCmdResult> callback) {
        return null;
    }
}