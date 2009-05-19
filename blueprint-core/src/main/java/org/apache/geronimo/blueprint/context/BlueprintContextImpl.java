/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.geronimo.blueprint.context;

import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;

import org.apache.geronimo.blueprint.BeanProcessor;
import org.apache.geronimo.blueprint.BlueprintConstants;
import org.apache.geronimo.blueprint.BlueprintContextEventSender;
import org.apache.geronimo.blueprint.ComponentDefinitionRegistryProcessor;
import org.apache.geronimo.blueprint.Destroyable;
import org.apache.geronimo.blueprint.ExtendedBlueprintContext;
import org.apache.geronimo.blueprint.NamespaceHandlerRegistry;
import org.apache.geronimo.blueprint.context.SatisfiableRecipe;
import org.apache.geronimo.blueprint.convert.ConversionServiceImpl;
import org.apache.geronimo.blueprint.di.DefaultExecutionContext;
import org.apache.geronimo.blueprint.di.DefaultRepository;
import org.apache.geronimo.blueprint.di.ExecutionContext;
import org.apache.geronimo.blueprint.di.NoSuchObjectException;
import org.apache.geronimo.blueprint.di.Recipe;
import org.apache.geronimo.blueprint.di.ReferenceNameRecipe;
import org.apache.geronimo.blueprint.di.ReferenceRecipe;
import org.apache.geronimo.blueprint.di.Repository;
import org.apache.geronimo.blueprint.namespace.ComponentDefinitionRegistryImpl;
import org.apache.geronimo.blueprint.utils.HeaderParser;
import org.apache.geronimo.blueprint.utils.HeaderParser.PathElement;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.ServiceFactory;
import org.osgi.service.blueprint.context.BlueprintContext;
import org.osgi.service.blueprint.context.ComponentDefinitionException;
import org.osgi.service.blueprint.context.NoSuchComponentException;
import org.osgi.service.blueprint.convert.ConversionService;
import org.osgi.service.blueprint.convert.Converter;
import org.osgi.service.blueprint.namespace.NamespaceHandler;
import org.osgi.service.blueprint.reflect.BeanMetadata;
import org.osgi.service.blueprint.reflect.ComponentMetadata;
import org.osgi.service.blueprint.reflect.RefMetadata;
import org.osgi.service.blueprint.reflect.ServiceMetadata;
import org.osgi.service.blueprint.reflect.ServiceReferenceMetadata;
import org.osgi.service.blueprint.reflect.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: javadoc
 *
 * @author <a href="mailto:dev@geronimo.apache.org">Apache Geronimo Project</a>
 * @version $Rev: 760378 $, $Date: 2009-03-31 11:31:38 +0200 (Tue, 31 Mar 2009) $
 */
public class BlueprintContextImpl implements ExtendedBlueprintContext, NamespaceHandlerRegistry.Listener, Runnable, SatisfiableRecipe.SatisfactionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlueprintContextImpl.class);

    private enum State {
        Unknown,
        WaitForNamespaceHandlers,
        Populated,
        WaitForInitialReferences,
        InitialReferencesSatisfied,
        WaitForInitialReferences2,
        InitialReferencesSatisfied2,
        WaitForTrigger,
        Create,
        Created,
        Failed,
        Destroyed,
    }

    private final BundleContext bundleContext;
    private final BlueprintContextEventSender sender;
    private final NamespaceHandlerRegistry handlers;
    private final List<URL> urls;
    private final boolean lazyActivation;
    private final ComponentDefinitionRegistryImpl componentDefinitionRegistry;
    private final ConversionServiceImpl conversionService;
    private final ExecutorService executors;
    private final Timer timer;
    private Set<URI> namespaces;
    private State state = State.Unknown;
    private Parser parser;
    private BlueprintObjectInstantiator instantiator;
    private ServiceRegistration registration;
    private boolean waitForNamespaceHandlersEventSent;
    private List<BeanProcessor> beanProcessors;
    private Map<String, Destroyable> destroyables = new HashMap<String, Destroyable>();
    private Map<String, List<SatisfiableRecipe>> satisfiables;
    private Map<ServiceMetadata, ServiceRegistrationProxy> services;
    private boolean serviceActivation;
    private Map<ServiceMetadata, TriggerService> triggerServices;
    private long timeout = 5 * 60 * 1000; 
    private boolean waitForDependencies = true;
    private TimerTask timerTask;

    public BlueprintContextImpl(BundleContext bundleContext, BlueprintContextEventSender sender, NamespaceHandlerRegistry handlers, ExecutorService executors, Timer timer, List<URL> urls, boolean lazyActivation) {
        this.bundleContext = bundleContext;
        this.sender = sender;
        this.handlers = handlers;
        this.urls = urls;
        this.conversionService = new ConversionServiceImpl(this);
        this.componentDefinitionRegistry = new ComponentDefinitionRegistryImpl();
        this.executors = executors;
        this.timer = timer;
        this.lazyActivation = lazyActivation;
        this.triggerServices = new HashMap<ServiceMetadata, TriggerService>();
        this.beanProcessors = new ArrayList<BeanProcessor>();
        this.services = Collections.synchronizedMap(new HashMap<ServiceMetadata, ServiceRegistrationProxy>());
    }

    public Class loadClass(String name) throws ClassNotFoundException {
        return bundleContext.getBundle().loadClass(name);
    }

    public void addDestroyable(String name, Destroyable destroyable) {
        destroyables.put(name, destroyable);
    }

    public List<BeanProcessor> getBeanProcessors() {
        return beanProcessors;
    }

    public BlueprintContextEventSender getSender() {
        return sender;
    }

    private void checkDirectives() {
        Bundle bundle = bundleContext.getBundle();
        Dictionary headers = bundle.getHeaders();
        String symbolicName = (String)headers.get(Constants.BUNDLE_SYMBOLICNAME);
        List<PathElement> paths = HeaderParser.parseHeader(symbolicName);
        
        String timeoutDirective = paths.get(0).getDirective(BlueprintConstants.TIMEOUT_DIRECTIVE);        
        if (timeoutDirective != null) {
            LOGGER.debug("Timeout directive: " + timeoutDirective);
            timeout = Integer.parseInt(timeoutDirective);
        }
        
        String waitForDependenciesDirective = paths.get(0).getDirective(BlueprintConstants.WAIT_FOR_DEPENDENCIES_DIRECTIVE);
        if (waitForDependenciesDirective != null) {
            LOGGER.debug("Wait-for-dependencies directive: " + waitForDependenciesDirective);
            waitForDependencies = Boolean.parseBoolean(waitForDependenciesDirective);
        }
        
        // TODO: add support for custom directive to disable schema validation?
    }
    
    public void run(boolean asynch) {
        if (asynch) {
            executors.submit(this);
        } else {
            run();
        }
    }
    
    public synchronized void run() {
        try {
            for (;;) {
                LOGGER.debug("Running blueprint context for bundle {} in state {}", bundleContext.getBundle().getSymbolicName(), state);
                switch (state) {
                    case Unknown:
                        checkDirectives();
                        sender.sendCreating(getBundleContext().getBundle());
                        parser = new Parser();
                        parser.parse(urls);
                        namespaces = parser.getNamespaces();
                        if (namespaces.size() > 0) {
                            handlers.addListener(this);
                        }
                        state = State.WaitForNamespaceHandlers;
                        break;
                    case WaitForNamespaceHandlers:
                        for (URI ns : namespaces) {
                            if (handlers.getNamespaceHandler(ns) == null) {
                                if (!waitForNamespaceHandlersEventSent) {
                                    sender.sendWaiting(getBundleContext().getBundle(), new String[] {NamespaceHandler.class.getName() }, null);
                                    waitForNamespaceHandlersEventSent = true;
                                }
                                return;
                            }
                        }
                        parser.populate(handlers, componentDefinitionRegistry);
                        state = State.Populated;
                        break;
                    case Populated:
                        instantiator = new BlueprintObjectInstantiator(conversionService, new RecipeBuilder(this).createRepository());
                        checkReferences();
                        trackServiceReferences();
                        timerTask = new TimerTask() {
                            public void run() {
                                synchronized (BlueprintContextImpl.this) {
                                    Throwable t = new TimeoutException();
                                    state = State.Failed;
                                    // TODO: clean up
                                    LOGGER.error("Unable to start blueprint context for bundle " + bundleContext.getBundle().getSymbolicName(), t);
                                    sender.sendFailure(getBundleContext().getBundle(), t);
                                }
                            }
                        };
                        timer.schedule(timerTask, timeout);
                        if (checkAllSatisfiables() || !waitForDependencies) {
                            state = State.InitialReferencesSatisfied;
                        } else {
                            // TODO: pass correct parameters
                            // TODO: do we need to send one event for each missing reference ?
                            sender.sendWaiting(getBundleContext().getBundle(), null, null);
                            state = State.WaitForInitialReferences;
                        }
                        break;
                    case WaitForInitialReferences:
                        if (checkAllSatisfiables()) {
                            state = State.InitialReferencesSatisfied;
                            break;
                        } else {
                            return;
                        }
                    case InitialReferencesSatisfied:
                        processTypeConverters();
                        processProcessors();
                        DefaultRepository repository = (DefaultRepository) instantiator.getRepository();
                        DefaultRepository tmpRepo = new RecipeBuilder(this).createRepository();

                        instantiator = new BlueprintObjectInstantiator(conversionService, new RecipeBuilder(this).createRepository());

                        untrackServiceReferences();
                        for (String name : repository.getNames()) {
                            Recipe recipe = repository.getRecipe(name);
                            Object instance = repository.getInstance(name);
                            if (instance != null) {
                                tmpRepo.putRecipe(name, recipe);
                                tmpRepo.putInstance(name, instance);
                            }
                        }
                        satisfiables = null;
                        instantiator = new BlueprintObjectInstantiator(conversionService, tmpRepo);
                        trackServiceReferences();
                        if (checkAllSatisfiables() || !waitForDependencies) {
                            state = State.InitialReferencesSatisfied2;
                        } else {
                            // TODO: pass correct parameters
                            // TODO: do we need to send one event for each missing reference ?
                            sender.sendWaiting(getBundleContext().getBundle(), null, null);
                            state = State.WaitForInitialReferences2;
                        }
                        break;
                    case WaitForInitialReferences2:
                        if (checkAllSatisfiables()) {
                            state = State.InitialReferencesSatisfied2;
                            break;
                        } else {
                            return;
                        }
                    case InitialReferencesSatisfied2:
                        // TODO: we should always register ServiceFactory in all cases.
                        //       the reason is that the trigger service creation may actually trigger the activation of
                        //       the bundle if the service properties reference any other components (thus loading a class
                        //       from the bundle and activating it)
                        //       the lazy activation should be a best effot and the lazy creation of services should be
                        //       done in all cases
                        //       Not sure about listeners
                        if (lazyActivation) {
                            registerTriggerServices();
                            state = State.WaitForTrigger;                            
                        } else {
                            state = State.Create;
                        }
                        break;
                    case WaitForTrigger:
                        return;
                    case Create:
                        timerTask.cancel();
                        instantiateComponents();

                        // Register the BlueprintContext in the OSGi registry
                        if (registration == null) {
                            Properties props = new Properties();
                            props.put(BlueprintConstants.CONTEXT_SYMBOLIC_NAME_PROPERTY,
                                      bundleContext.getBundle().getSymbolicName());
                            props.put(BlueprintConstants.CONTEXT_VERSION_PROPERTY,
                                      bundleContext.getBundle().getHeaders().get(Constants.BUNDLE_VERSION));
                            // TODO: register a service factory so that we can honor the bundle scope when
                            //    BlueprintContext.getComponent(String) is called directly
                            registration = bundleContext.registerService(BlueprintContext.class.getName(), this, props);

                            sender.sendCreated(getBundleContext().getBundle());
                            state = State.Created;
                        }
                        break;
                    case Created:
                    case Failed:
                    case Destroyed:
                        return;
                }
            }
        } catch (Throwable t) {
            state = State.Failed;
            // TODO: clean up
            LOGGER.error("Unable to start blueprint context for bundle " + bundleContext.getBundle().getSymbolicName(), t);
            sender.sendFailure(getBundleContext().getBundle(), t);
        }
    }

    private void checkReferences() throws Exception {
        DefaultRepository repository = (DefaultRepository) instantiator.getRepository();
        List<Recipe> recipes = new ArrayList<Recipe>();
        boolean createNewContext = !ExecutionContext.isContextSet();
        if (createNewContext) {
            ExecutionContext.setContext(new DefaultExecutionContext(conversionService, instantiator.getRepository()));
        }
        try {
            for (String name : repository.getNames()) {
                Recipe recipe = repository.getRecipe(name);
                if (recipe != null) {
                    getAllRecipes(recipe, recipes);
                }
            }
        } finally {
            if (createNewContext) {
                ExecutionContext.setContext(null);
            }
        }
        for (Recipe recipe : recipes) {
            String ref = null;
            if (recipe instanceof ReferenceRecipe) {
                ref = ((ReferenceRecipe) recipe).getReferenceName();
            } else if (recipe instanceof ReferenceNameRecipe) {
                ref = ((ReferenceNameRecipe) recipe).getReferenceName();
            }
            if (ref != null && repository.get(ref) == null) {
                throw new ComponentDefinitionException("Unresolved ref/idref to component: " + ref);
            }
        }
    }

    private void getAllRecipes(Recipe recipe, List<Recipe> recipes) {
        if (!recipes.contains(recipe)) {
            recipes.add(recipe);
            for (Recipe r : recipe.getNestedRecipes()) {
                getAllRecipes(r, recipes);
            }
        }
    }

    private void processTypeConverters() throws Exception {
        List<String> typeConverters = new ArrayList<String>();
        for (Target target : componentDefinitionRegistry.getTypeConverters()) {
            if (target instanceof ComponentMetadata) {
                typeConverters.add(((ComponentMetadata) target).getId());
            } else if (target instanceof RefMetadata) {
                typeConverters.add(((RefMetadata) target).getComponentId());
            } else {
                throw new ComponentDefinitionException("Unexpected metadata for type converter: " + target);
            }
        }

        Map<String, Object> objects = instantiator.createAll(typeConverters.toArray(new String[typeConverters.size()]));
        for (Object obj : objects.values()) {
            if (obj instanceof Converter) {
                conversionService.registerConverter((Converter) obj);
            } else {
                throw new ComponentDefinitionException("Type converter " + obj + " does not implement the " + Converter.class.getName() + " interface");
            }
        }
    }

    private void processProcessors() throws Exception {
        // Instanciate ComponentDefinitionRegistryProcessor and BeanProcessor
        for (BeanMetadata bean : getBeanComponentsMetadata()) {
            Class clazz = bean.getRuntimeClass();
            if (clazz == null && bean.getClassName() != null) {
                clazz = loadClass(bean.getClassName());
            }
            if (clazz == null) {
                continue;
            }
            if (ComponentDefinitionRegistryProcessor.class.isAssignableFrom(clazz)) {
                Object obj = instantiator.create(bean.getId());
                ((ComponentDefinitionRegistryProcessor) obj).process(componentDefinitionRegistry);
            } else if (BeanProcessor.class.isAssignableFrom(clazz)) {
                Object obj = instantiator.create(bean.getId());
                this.beanProcessors.add((BeanProcessor) obj);
            }
        }
    }

    private Map<String, List<SatisfiableRecipe>> getSatisfiableDependenciesMap() {
        if (satisfiables == null && instantiator != null) {
            boolean createNewContext = !ExecutionContext.isContextSet();
            if (createNewContext) {
                ExecutionContext.setContext(new DefaultExecutionContext(conversionService, instantiator.getRepository()));
            }
            try {
                satisfiables = new HashMap<String, List<SatisfiableRecipe>>();
                for (String name : componentDefinitionRegistry.getComponentDefinitionNames()) {
                    Recipe r = ((DefaultRepository) instantiator.getRepository()).getRecipe(name);
                    List<SatisfiableRecipe> recipes = new ArrayList<SatisfiableRecipe>();
                    if (r instanceof SatisfiableRecipe) {
                        recipes.add((SatisfiableRecipe) r);
                    }
                    getSatisfiableDependencies(r, recipes, new HashSet<Recipe>());
                    if (!recipes.isEmpty()) {
                        satisfiables.put(name, recipes);
                    }
                }
                return satisfiables;
            } finally {
                if (createNewContext) {
                    ExecutionContext.setContext(null);
                }
            }
        }
        return satisfiables;
    }

    private void getSatisfiableDependencies(Recipe r, List<SatisfiableRecipe> recipes, Set<Recipe> visited) {
        if (!visited.contains(r)) {
            visited.add(r);
            for (Recipe dep : r.getNestedRecipes()) {
                if (dep instanceof SatisfiableRecipe) {
                    recipes.add((SatisfiableRecipe) dep);
                }
                getSatisfiableDependencies(dep, recipes, visited);
            }
        }
    }

    private void trackServiceReferences() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        List<String> satisfiables = new ArrayList<String>();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe satisfiable : dependencies.get(name)) {
                satisfiable.registerListener(this);
                satisfiable.start();
                satisfiables.add(satisfiable.getName());
            }
        }
        LOGGER.debug("Tracking service references: {}", satisfiables);
    }
    
    private void untrackServiceReferences() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        if (dependencies != null) {
            for (String name : dependencies.keySet()) {
                for (SatisfiableRecipe satisfiable : dependencies.get(name)) {
                    satisfiable.unregisterListener(this);
                    satisfiable.stop();
                }
            }
        }
    }

    private boolean checkAllSatisfiables() {
        Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
        for (String name : dependencies.keySet()) {
            for (SatisfiableRecipe recipe : dependencies.get(name)) {
                if (!recipe.isSatisfied()) {
                    return false;
                }
            }
        }
        return true;
    }

    public void notifySatisfaction(SatisfiableRecipe satisfiable) {
        LOGGER.debug("Notified satisfaction {} in bundle {}: {}",
                new Object[] { satisfiable.getName(), bundleContext.getBundle().getSymbolicName(), satisfiable.isSatisfied() });
        if (state == State.WaitForInitialReferences) {
            executors.submit(this);
        } else if (state == State.Created) {
            Map<String, List<SatisfiableRecipe>> dependencies = getSatisfiableDependenciesMap();
            for (String name : dependencies.keySet()) {
                ComponentMetadata metadata = componentDefinitionRegistry.getComponentDefinition(name);
                if (metadata instanceof ServiceMetadata) {
                    boolean satisfied = true;
                    for (SatisfiableRecipe recipe : dependencies.get(name)) {
                        if (!recipe.isSatisfied()) {
                            satisfied = false;
                            break;
                        }
                    }
                    ServiceRegistrationProxy reg = (ServiceRegistrationProxy) getComponent(name);
                    if (satisfied && !reg.isRegistered()) {
                        LOGGER.debug("Registering service {} due to satisfied references", name);
                        reg.register();
                    } else if (!satisfied && reg.isRegistered()) {
                        LOGGER.debug("Unregistering service {} due to unsatisfied references", name);
                        reg.unregister();
                    }
                }
            }
        }
    }

    private void instantiateComponents() {
        List<String> components = new ArrayList<String>();
        for (String name : componentDefinitionRegistry.getComponentDefinitionNames()) {
            ComponentMetadata component = componentDefinitionRegistry.getComponentDefinition(name);
            if (component instanceof BeanMetadata) {
                BeanMetadata local = (BeanMetadata) component;
                String scope = local.getScope();
                if (!local.isLazyInit() && BeanMetadata.SCOPE_SINGLETON.equals(scope)) {
                    components.add(name);
                }
            } else {
                components.add(name);
            }
        }
        LOGGER.debug("Instantiating components: {}", components);
        try {
            instantiator.createAll(components);
        } catch (ComponentDefinitionException e) {
            throw e;
        } catch (Throwable t) {
            throw (ComponentDefinitionException) new ComponentDefinitionException("Unable to instantiate components").initCause(t);
        }
    }

    private void destroyComponents() {
        if (instantiator != null) {
            ((DefaultRepository)instantiator.getRepository()).destroy();
        }
        
        Map<String, Destroyable> destroyables = new HashMap<String, Destroyable>(this.destroyables);
        this.destroyables.clear();
        for (Map.Entry<String, Destroyable> entry : destroyables.entrySet()) {
            try {
                entry.getValue().destroy();
            } catch (Exception e) {
                LOGGER.info("Error destroying bean " + entry.getKey(), e);
            }
        }
    }
    
    protected void registerService(ServiceRegistrationProxy registration) { 
        ServiceMetadata metadata = registration.getMetadata();
        if (services.put(metadata, registration) != null) {
            LOGGER.warn("Service for this metadata is already registered {}", metadata);
        }
    }
        
    private void unregisterServices() {
        for (ServiceRegistrationProxy proxy : services.values()) {
            proxy.unregister();
        }
    }
        
    private void registerTriggerServices() {
        // TODO: right now this only returns top-level services
        for (ServiceMetadata service : getExportedServicesMetadata()) {
            // Trigger services are only created for services without listeners and explicitly defined interface classes
            if (service.getRegistrationListeners().isEmpty() && !service.getInterfaceNames().isEmpty()) {
                TriggerService triggerService = new TriggerService(service, this);
                triggerService.register();
                triggerServices.put(service, triggerService);
            }
        }
    }
    
    private void unregisterTriggerServices() {
        for (TriggerService service : triggerServices.values()) {
            service.unregister();
        }
        triggerServices.clear();
    }
        
    protected TriggerService removeTriggerService(ServiceMetadata metadata) {
        return triggerServices.remove(metadata);
    }
    
    protected void forceActivation(boolean serviceActivation) throws BundleException {
        this.serviceActivation = serviceActivation;
        getBundleContext().getBundle().start(Bundle.START_TRANSIENT);
    }
    
    public synchronized void triggerActivation() {
        if (!lazyActivation) {
            throw new IllegalStateException("triggerActivation can only be called for bundles with lazy activation policy");
        }
        if (state == State.WaitForTrigger) {
            LOGGER.debug("Activation triggered (service activation: {})", serviceActivation); 
            state = State.Create;
            // service triggered activation runs synchronously but classloader triggered activation runs asynchronously
            run(serviceActivation ? false : true);
        }
    }
    
    public Set<String> getComponentNames() {
        return componentDefinitionRegistry.getComponentDefinitionNames();
    }
    
    public Object getComponent(String name) throws NoSuchComponentException {
        if (instantiator == null) {
            throw new NoSuchComponentException(name);
        }
        try {
            return instantiator.create(name);
        } catch (NoSuchObjectException e) {
            throw new NoSuchComponentException(name);
        } catch (ComponentDefinitionException e) {
            throw e;
        } catch (Throwable t) {
            throw (ComponentDefinitionException) new ComponentDefinitionException("Cound not create component instance for " + name).initCause(t);
        }
    }

    public ComponentMetadata getComponentMetadata(String name) {
        ComponentMetadata metadata = componentDefinitionRegistry.getComponentDefinition(name);
        if (metadata == null) {
            throw new NoSuchComponentException(name);
        }
        return metadata;
    }

    public Collection<ServiceReferenceMetadata> getReferencedServicesMetadata() {
        return getComponentsMetadata(ServiceReferenceMetadata.class);
    }

    public Collection<ServiceMetadata> getExportedServicesMetadata() {
        return getComponentsMetadata(ServiceMetadata.class);
    }

    public Collection<BeanMetadata> getBeanComponentsMetadata() {
        return getComponentsMetadata(BeanMetadata.class);
    }

    public <T extends ComponentMetadata> List<T> getComponentsMetadata(Class<T> clazz) {
        List<T> metadatas = new ArrayList<T>();
        for (String name : componentDefinitionRegistry.getComponentDefinitionNames()) {
            ComponentMetadata component = componentDefinitionRegistry.getComponentDefinition(name);
            if (clazz.isInstance(component)) {
                metadatas.add(clazz.cast(component));
            }
        }
        metadatas = Collections.unmodifiableList(metadatas);
        return metadatas;

    }

    protected Repository getRepository() {
        return instantiator.getRepository();
    }
    
    public ConversionService getConversionService() {
        return conversionService;
    }
    
    public ComponentDefinitionRegistryImpl getComponentDefinitionRegistry() {
        return componentDefinitionRegistry;
    }
        
    public BundleContext getBundleContext() {
        return bundleContext;
    }
    
    public synchronized void destroy() {
        state = State.Destroyed;
        sender.sendDestroying(getBundleContext().getBundle());

        if (timerTask != null) {
            timerTask.cancel();
        }
        if (registration != null) {
            registration.unregister();
        }
        handlers.removeListener(this);        
        untrackServiceReferences();
        unregisterServices();  
        unregisterTriggerServices();
        destroyComponents();
        
        sender.sendDestroyed(getBundleContext().getBundle());
        LOGGER.debug("Module context destroyed: " + this.bundleContext);
    }

    public synchronized void namespaceHandlerRegistered(URI uri) {
        if (namespaces != null && namespaces.contains(uri)) {
            executors.submit(this);
        }
    }

    public synchronized void namespaceHandlerUnregistered(URI uri) {
        if (namespaces != null && namespaces.contains(uri)) {
            unregisterServices();
            destroyComponents();
            // TODO: stop all reference / collections
            // TODO: clear the repository
            waitForNamespaceHandlersEventSent = false;
            state = State.WaitForNamespaceHandlers;
            executors.submit(this);
        }
    }

}


/* TODO: fix the following deadlock

      [bnd] "pool-3-thread-1" prio=5 tid=0x01018790 nid=0x8a2c00 in Object.wait() [0xb0f90000..0xb0f90d90]
      [bnd] 	at java.lang.Object.wait(Native Method)
      [bnd] 	- waiting on <0x25671928> (a java.lang.Object)
      [bnd] 	at org.apache.geronimo.blueprint.context.UnaryServiceReferenceRecipe.getService(UnaryServiceReferenceRecipe.java:197)
      [bnd] 	- locked <0x25671928> (a java.lang.Object)
      [bnd] 	at org.apache.geronimo.blueprint.context.UnaryServiceReferenceRecipe.access$000(UnaryServiceReferenceRecipe.java:55)
      [bnd] 	at org.apache.geronimo.blueprint.context.UnaryServiceReferenceRecipe$ServiceDispatcher.loadObject(UnaryServiceReferenceRecipe.java:225)
      [bnd] 	at org.osgi.test.cases.blueprint.services.ServiceManager$$EnhancerByCGLIB$$f740783d.getActiveServices(<generated>)
      [bnd] 	at org.osgi.test.cases.blueprint.components.serviceimport.NullReferenceList.init(NullReferenceList.java:43)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:39)
      [bnd] 	at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:25)
      [bnd] 	at java.lang.reflect.Method.invoke(Method.java:585)
      [bnd] 	at org.apache.geronimo.blueprint.context.BlueprintObjectRecipe.internalCreate(BlueprintObjectRecipe.java:586)
      [bnd] 	at org.apache.geronimo.blueprint.di.AbstractRecipe.create(AbstractRecipe.java:95)
      [bnd] 	at org.apache.geronimo.blueprint.context.BlueprintObjectInstantiator.createInstance(BlueprintObjectInstantiator.java:83)
      [bnd] 	at org.apache.geronimo.blueprint.context.BlueprintObjectInstantiator.createAll(BlueprintObjectInstantiator.java:65)
      [bnd] 	at org.apache.geronimo.blueprint.context.BlueprintContextImpl.instantiateComponents(BlueprintContextImpl.java:541)
      [bnd] 	at org.apache.geronimo.blueprint.context.BlueprintContextImpl.run(BlueprintContextImpl.java:303)
      [bnd] 	- locked <0x25730658> (a org.apache.geronimo.blueprint.context.BlueprintContextImpl)
      [bnd] 	at java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:417)
      [bnd] 	at java.util.concurrent.FutureTask$Sync.innerRun(FutureTask.java:269)
      [bnd] 	at java.util.concurrent.FutureTask.run(FutureTask.java:123)
      [bnd] 	at java.util.concurrent.ThreadPoolExecutor$Worker.runTask(ThreadPoolExecutor.java:650)
      [bnd] 	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:675)
      [bnd] 	at java.lang.Thread.run(Thread.java:613)
      [bnd]
      [bnd] "main" prio=5 tid=0x01001460 nid=0xb0801000 waiting for monitor entry [0xb07ff000..0xb0800148]
      [bnd] 	at org.apache.geronimo.blueprint.context.BlueprintContextImpl.destroy(BlueprintContextImpl.java:687)
      [bnd] 	- waiting to lock <0x25730658> (a org.apache.geronimo.blueprint.context.BlueprintContextImpl)
      [bnd] 	at org.apache.geronimo.blueprint.BlueprintExtender.destroyContext(BlueprintExtender.java:121)
      [bnd] 	at org.apache.geronimo.blueprint.BlueprintExtender.bundleChanged(BlueprintExtender.java:113)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.BundleContextImpl.dispatchEvent(BundleContextImpl.java:916)
      [bnd] 	at org.eclipse.osgi.framework.eventmgr.EventManager.dispatchEvent(EventManager.java:220)
      [bnd] 	at org.eclipse.osgi.framework.eventmgr.ListenerQueue.dispatchEventSynchronous(ListenerQueue.java:149)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.Framework.publishBundleEventPrivileged(Framework.java:1350)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.Framework.publishBundleEvent(Framework.java:1301)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.BundleHost.stopWorker(BundleHost.java:470)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.AbstractBundle.uninstallWorker(AbstractBundle.java:784)
      [bnd] 	at org.eclipse.osgi.framework.internal.core.AbstractBundle.uninstall(AbstractBundle.java:764)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BlueprintMetadata.cleanup(BlueprintMetadata.java:670)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.EventSet.stop(EventSet.java:97)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.TestPhase.stopEventSets(TestPhase.java:119)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.TestPhase.cleanup(TestPhase.java:98)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BaseTestController.cleanup(BaseTestController.java:219)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.StandardTestController.cleanup(StandardTestController.java:177)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BaseTestController.terminate(BaseTestController.java:340)
      [bnd] 	at org.osgi.test.cases.blueprint.framework.BaseTestController.run(BaseTestController.java:363)
      [bnd] 	at org.osgi.test.cases.blueprint.tests.TestReferenceCollection.testEmptyListCollectionServiceListener(TestReferenceCollection.java:527)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
      [bnd] 	at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:39)
      [bnd] 	at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:25)
      [bnd] 	at java.lang.reflect.Method.invoke(Method.java:585)
      [bnd] 	at junit.framework.TestCase.runTest(TestCase.java:164)
      [bnd] 	at junit.framework.TestCase.runBare(TestCase.java:130)
      [bnd] 	at junit.framework.TestResult$1.protect(TestResult.java:106)
      [bnd] 	at junit.framework.TestResult.runProtected(TestResult.java:124)
      [bnd] 	at junit.framework.TestResult.run(TestResult.java:109)
      [bnd] 	at junit.framework.TestCase.run(TestCase.java:120)
      [bnd] 	at junit.framework.TestSuite.runTest(TestSuite.java:230)
      [bnd] 	at junit.framework.TestSuite.run(TestSuite.java:225)
      [bnd] 	at junit.framework.TestSuite.runTest(TestSuite.java:230)
      [bnd] 	at junit.framework.TestSuite.run(TestSuite.java:225)
      [bnd] 	at aQute.junit.runtime.Target.doTesting(Target.java:157)
      [bnd] 	at aQute.junit.runtime.Target.run(Target.java:40)
      [bnd] 	at aQute.junit.runtime.Target.main(Target.java:33)

*/