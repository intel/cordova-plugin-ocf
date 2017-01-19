package com.intel.cordova.plugin.ocf;

// Java
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

// Cordova
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

// Android
import android.content.Context;
import android.util.Log;

// Iotivity
import org.iotivity.base.ModeType;
import org.iotivity.base.ObserveType;
import org.iotivity.base.OcConnectivityType;
import org.iotivity.base.OcException;
import org.iotivity.base.OcHeaderOption;
import org.iotivity.base.OcPlatform;
import org.iotivity.base.OcRepresentation;
import org.iotivity.base.OcResource;
import org.iotivity.base.PlatformConfig;
import org.iotivity.base.QualityOfService;
import org.iotivity.base.ServiceType;

// Third party
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class OcfBackendIotivity
    implements OcfBackendInterface,
               OcPlatform.OnDeviceFoundListener,
               OcPlatform.OnResourceFoundListener,
               OcResource.OnGetListener,
               OcResource.OnPutListener,
               OcResource.OnObserveListener
{
    // The callback contexts are stored so we're able to provide data to the
    // frontend in an asynchronous and unsolicited fashion.
    private CallbackContext findDevicesCallbackContext;
    private CallbackContext findResourcesCallbackContext;

    private static final String OC_RSRVD_DEVICE_ID = "di";
    private static final String OC_RSRVD_DEVICE_NAME = "n";
    private static final String OC_RSRVD_SPEC_VERSION = "lcv";
    private static final String OC_RSRVD_DATA_MODEL_VERSION = "dmv";

    private OcfPlugin plugin;

    // We keep a list of all seen resources, so that they can be reference
    // counted by the Iotivity JNI backend properly and we don't incur in
    // memory leaks or corruptions.
    private List<OcResource> seenResources = new ArrayList<OcResource>();

    // We keep a list of resources that we are observing so we don't have
    // multiple parallel observations on the same resource.
    private List<String> observedResources = new ArrayList<String>();

    // We keep a map of native resources to OCF resources so we don't have to
    // generate a pair each time we need it (it's expensive because that
    // will need to do a `get` on the native resource, which involves spawning
    // a new thread and waiting around for tha to complete).
    private Map<OcResource, OcfResource> nativeToOcfResourceMap =
        new HashMap<OcResource, OcfResource>();

    // We keep a list of resource updates, which are the result of
    // observations, that then the frontend will consume via pollig.
    private List<Map<OcfResource, OcfResourceRepresentation> > resourceUpdates =
        new ArrayList<Map<OcfResource, OcfResourceRepresentation> >();

    // We keep track of the completion status of the `resource.get` and
    // `resource.put` calls, because we wait on them.
    private Map<String, Boolean> resourceGetFinished = new HashMap<String, Boolean>();
    private Map<String, Boolean> resourcePutFinished = new HashMap<String, Boolean>();


    // Constructor
    public OcfBackendIotivity(OcfPlugin plugin) {
        this.plugin = plugin;

        PlatformConfig platformConfig = new PlatformConfig(
            plugin.cordova.getActivity().getApplicationContext(),
            ServiceType.IN_PROC,
            ModeType.CLIENT,
            "0.0.0.0", // By setting to "0.0.0.0", it binds to all available interfaces
            0,         // Uses randomly available port
            QualityOfService.LOW
        );
        OcPlatform.Configure(platformConfig);
    }


    // ------------------------------------------------------------------------
    // TODO: conversion functions should be in backend specific subclasses of
    // Ocf* classes.
    // ------------------------------------------------------------------------

    private static OcResource resourceToNative(OcfResource ocfResource) {
        OcResource nativeResource = null;
        String url = ocfResource.getId().getDeviceId();
        String host = ocfResource.getId().getResourcePath();
        ArrayList<String> resourceTypes = ocfResource.getResourceTypes();
        ArrayList<String> interfaces = ocfResource.getInterfaces();

        try {
            nativeResource = OcPlatform.constructResourceObject(
                ocfResource.getId().getDeviceId(),
                ocfResource.getId().getResourcePath(),
                EnumSet.of(OcConnectivityType.CT_DEFAULT),
                false,
                ocfResource.getResourceTypes(),
                ocfResource.getInterfaces());
        } catch (OcException ex) {
            Log.e("CordovaPluginOCF", ex.toString());
        }

        return nativeResource;
    }

    private static OcRepresentation representationToNative(
        OcfResourceRepresentation repr)
    {
        OcRepresentation nativeRepr = new OcRepresentation();
        for (Map.Entry<String, Object> entry : repr.getProperties().entrySet())
        {
            String key = entry.getKey();
            Object value = entry.getValue();
            String type = value.getClass().getName();
            String stringValue = "" + value;

            // Parse value and support only primitive types for now.
            try {
                boolean done = false;

                if (stringValue.toLowerCase().equals("true")) {
                    nativeRepr.setValue(key, true);
                    done = true;
                } else if (stringValue.toLowerCase().equals("false")) {
                    nativeRepr.setValue(key, false);
                    done = true;
                }

                try {
                    int i = Integer.parseInt(stringValue);
                    nativeRepr.setValue(key, i);
                    done = true;
                } catch(NumberFormatException ex) {
                    Log.w("CordovaPluginOCF", "Value is not an integer");
                }

                try {
                    double d = Double.parseDouble(stringValue);
                    nativeRepr.setValue(key, d);
                    done = true;
                } catch(NumberFormatException ex) {
                    Log.w("CordovaPluginOCF", "Value is not a double");
                }

                if (!done) {
                    nativeRepr.setValue(key, stringValue);
                }
            } catch(OcException ex) {
                Log.e("CordovaPluginOCF", ex.toString());
            }
        }

        return nativeRepr;
    }

    private static OcfResourceRepresentation representationFromNative(
        OcRepresentation nativeRepr)
    {
        OcfResourceRepresentation repr = new OcfResourceRepresentation();
        Map<String, Object> values = nativeRepr.getValues();
        for (Map.Entry<String, Object> entry: values.entrySet()) {
            repr.setValue(entry.getKey(), entry.getValue());
        }
        return repr;
    }


    // Member utils

    private String getResourceKey(OcResource resource) {
        String host = resource.getHost();
        String resourcePath = resource.getUri();
        return host + resourcePath;
    }

    private String getResourceKey(OcRepresentation repr) {
        String host = repr.getHost();
        String resourcePath = repr.getUri();
        return host + resourcePath;
    }

    private OcResource getSeenResourceByKey(String key) {
        for (OcResource resource: this.seenResources) {
            String resourceKey = this.getResourceKey(resource);
            if (resourceKey.equals(key)) {
                return resource;
            }
        }

        return null;
    }

    private OcfResource getOcfResourceFromNative(OcResource nativeResource) {
        return this.nativeToOcfResourceMap.get(nativeResource);
    }

    private OcfResource produceOcfResourceFromNative(final OcResource nativeResource) {
        OcfResource ocfResource = null;

        ocfResource = this.getOcfResourceFromNative(nativeResource);
        if (ocfResource == null) {
            final OcfBackendIotivity self = this;

            final String host = nativeResource.getHost();
            final String uri = nativeResource.getUri();
            final String key = this.getResourceKey(nativeResource);

            ocfResource = new OcfResource(host, uri);
            ocfResource.setResourceTypes(new ArrayList<String> (nativeResource.getResourceTypes()));
            ocfResource.setInterfaces(new ArrayList<String> (nativeResource.getResourceInterfaces()));
            ocfResource.setObservable(nativeResource.isObservable());

            this.nativeToOcfResourceMap.put(nativeResource, ocfResource);

            // Get all poperties
            this.plugin.cordova.getThreadPool().execute(new Runnable() {
                public void run() {
                    int elapsed = 0;
                    final int timeout = 5000;
                    final int sleepTime = 10;

                    self.resourceGetFinished.put(key, false);

                    try {
                        nativeResource.get(new HashMap<String, String>(), self);
                        while(self.resourceGetFinished.get(key) == false && elapsed <= timeout) {
                            Thread.sleep(sleepTime);
                            elapsed += sleepTime;
                        }

                        if (elapsed > timeout) {
                            self.resourceGetFinished.put(key, true);
                            Log.d("CordovaPluginOCF", "GET timeout reached");
                        }

                    } catch (OcException ex) {
                        Log.e("CordovaPluginOCF", ex.toString());
                        self.resourceGetFinished.put(key, true);
                    } catch (InterruptedException ex) {
                        Log.e("CordovaPluginOCF", ex.toString());
                        self.resourceGetFinished.put(key, true);
                    }
                }
            });
        }

        return ocfResource;
    }

    private void addResourceUpdate(OcfResource resource, OcRepresentation repr)
    {
        Map<OcfResource, OcfResourceRepresentation> update =
            new HashMap<OcfResource, OcfResourceRepresentation>();

        update.put(resource, this.representationFromNative(repr));
        this.resourceUpdates.add(update);
    }


    // Listener callbacks for the resource objects

    @Override
    public synchronized void onGetCompleted(
        java.util.List<OcHeaderOption> headerOptionList,
        OcRepresentation ocRepresentation)
    {
        String key = this.getResourceKey(ocRepresentation);

        Log.d("CordovaPluginOCF", "onGetCompleted: " + key);

        OcResource nativeResource = this.getSeenResourceByKey(key);
        OcfResource ocfResource = this.getOcfResourceFromNative(nativeResource);

        if (ocfResource != null) {
            Map<String, Object> values = ocRepresentation.getValues();
            for(Map.Entry<String, Object> entry: values.entrySet()) {
                String reprKey = entry.getKey();
                Object reprValue = entry.getValue();
                ocfResource.setProperty(reprKey, reprValue);
            }
            this.resourceGetFinished.put(key, true);
        } else {
            Log.e("CordovaPluginOCF", "onGetCompleted: unable to find resource");
        }
    }

    @Override
    public synchronized void onGetFailed(java.lang.Throwable ex) {
        Log.e("CordovaPluginOCF", "onGetFailed");
        // Note: the marking of the get as finished is actually performed in
        // the `produceOcfResourceFromNative` method, as in this callback we
        // have no context.
    }

    @Override
    public synchronized void onPutCompleted(
        java.util.List<OcHeaderOption> headerOptionList,
        OcRepresentation ocRepresentation)
    {
        Log.d("CordovaPluginOCF", "onPutCompleted");

        String key = this.getResourceKey(ocRepresentation);
        this.resourcePutFinished.put(key, true);
    }

    @Override
    public synchronized void onPutFailed(java.lang.Throwable ex) {
        Log.e("CordovaPluginOCF", "onPutFailed");
        // Note: the marking of the get as finished is actually performed in
        // the `updateResource` method, as in this callback we have no context.
    }

    @Override
    public synchronized void onObserveCompleted(
           java.util.List<OcHeaderOption> headerOptionList,
           OcRepresentation ocRepresentation,
           int sequenceNumber)
    {
        Log.d("CordovaPluginOCF", "onObserveCompleted");

        String key = this.getResourceKey(ocRepresentation);
        OcResource nativeResource = this.getSeenResourceByKey(key);
        OcfResource ocfResource = this.getOcfResourceFromNative(nativeResource);

        this.addResourceUpdate(ocfResource, ocRepresentation);
    }

    @Override
    public synchronized void onObserveFailed(java.lang.Throwable ex) {
        Log.e("CordovaPluginOCF", "onObserveFailed");
    }


    // Listener callbacks for the platform object

    @Override
    public void onDeviceFound(final OcRepresentation repr) {
        OcfDevice device = new OcfDevice();
        try {
            device.setUuid((String) repr.getValue(OC_RSRVD_DEVICE_ID));
            device.setName((String) repr.getValue(OC_RSRVD_DEVICE_NAME));
            device.setCoreSpecVersion((String) repr.getValue(OC_RSRVD_SPEC_VERSION));
            device.setDataModels(new ArrayList<String>() {{
                add((String) repr.getValue(OC_RSRVD_DATA_MODEL_VERSION));
            }});
        } catch (OcException ex) {
            Log.e("CordovaPluginOCF", "Error reading OcRepresentation");
        }

        OcfDeviceEvent ev = new OcfDeviceEvent(device);
        try {
            PluginResult result = new PluginResult(PluginResult.Status.OK, ev.toJSON());
            result.setKeepCallback(true);
            this.findDevicesCallbackContext.sendPluginResult(result);
        } catch (JSONException ex) {
            this.findDevicesCallbackContext.error(ex.getMessage());
        }
    }

    @Override
    public synchronized void onResourceFound(OcResource resource) {
        String resourcePath = resource.getUri();
        String key = this.getResourceKey(resource);

        if (resourcePath.equals("/oic/p") ||
            resourcePath.equals("/oic/d") ||
            resourcePath.equals("/oic/sec/doxm") ||
            resourcePath.equals("/oic/sec/pstat"))
        {
            return;
        }

        OcResource seenResource = this.getSeenResourceByKey(key);
        if (seenResource != null) {
            return;
        }

        Log.d("CordovaPluginOCF", "Found resource: " + key);
        this.seenResources.add(resource);

        if (resource.isObservable() && ! this.observedResources.contains(key)) {
            try {
                Log.d("CordovaPluginOCF", "Observing resource: " + key);
                resource.observe(ObserveType.OBSERVE, new HashMap<String, String>(), this);
                this.observedResources.add(key);
            } catch (OcException e) {
                Log.e("CordovaPluginOCF", "Unable to observe resoure");
            }
        }

        OcfResource ocfResource = this.produceOcfResourceFromNative(resource);
        OcfResourceEvent ev = new OcfResourceEvent(ocfResource);

        try {
            PluginResult result = new PluginResult(PluginResult.Status.OK, ev.toJSON());
            result.setKeepCallback(true);
            this.findResourcesCallbackContext.sendPluginResult(result);
        } catch (JSONException ex) {
            this.findResourcesCallbackContext.error(ex.getMessage());
        }
    }


    // API

    public void findDevices(CallbackContext cc) {
        this.findDevicesCallbackContext = cc;
        try {
            OcPlatform.getDeviceInfo(
                "", "/oic/d", EnumSet.of(OcConnectivityType.CT_DEFAULT), this);
        } catch (OcException ex) {
            this.findDevicesCallbackContext.error(ex.getMessage());
        }
    }


    public void findResources(JSONArray args, CallbackContext cc)
        throws JSONException
    {
        String deviceId = args.getJSONObject(0).optString("deviceId");
        String resourceType = args.getJSONObject(0).optString("resourceType");

        this.findResourcesCallbackContext = cc;

        try {
            OcPlatform.findResource(
                deviceId,
                OcPlatform.WELL_KNOWN_QUERY + "?rt=" + resourceType,
                EnumSet.of(OcConnectivityType.CT_DEFAULT),
                this);
        } catch (OcException ex) {
            this.findResourcesCallbackContext.error(ex.getMessage());
        }
    }

    public void updateResource(JSONArray args, CallbackContext cc)
        throws JSONException
    {
        int elapsed = 0;
        final int timeout = 5000;
        final int sleepTime = 100;

        OcfResource ocfResource = OcfResource.fromJSON(args.getJSONObject(0));
        OcResource nativeResource = OcfBackendIotivity.resourceToNative(ocfResource);
        OcRepresentation nativeRepr = OcfBackendIotivity.representationToNative(
            ocfResource.getProperties());
        String key = this.getResourceKey(nativeResource);

        Log.d("CordovaPluginOCF", "Updating resource: " +  ocfResource.toJSON().toString());

        try {
            this.resourcePutFinished.put(key, false);
            nativeResource.put(nativeRepr, new HashMap<String, String>(), this);
            while(this.resourcePutFinished.get(key) == false && elapsed <= timeout) {
                Thread.sleep(sleepTime);
                elapsed += sleepTime;
            }

            if (elapsed > timeout) {
                this.resourcePutFinished.put(key, true);
            }
        } catch (OcException ex) {
            Log.e("CordovaPluginOCF", ex.toString());
            this.resourcePutFinished.put(key, true);
        } catch (InterruptedException ex) {
            Log.e("CordovaPluginOCF", ex.toString());
            this.resourcePutFinished.put(key, true);
        }

        cc.sendPluginResult(new PluginResult(PluginResult.Status.OK));
    }

    public JSONArray getResourceUpdates() throws JSONException {
        JSONArray updates = new JSONArray();

        for(Map<OcfResource, OcfResourceRepresentation> map: this.resourceUpdates) {
            for(Map.Entry<OcfResource, OcfResourceRepresentation> entry: map.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put(
                    entry.getKey().getId().getUniqueKey(),
                    entry.getValue().toJSON());
                updates.put(obj);
            }
        }

        this.resourceUpdates.clear();
        return updates;
    }
}
