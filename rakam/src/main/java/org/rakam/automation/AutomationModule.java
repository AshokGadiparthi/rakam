package org.rakam.automation;


import com.google.auto.service.AutoService;
import com.google.inject.Binder;
import com.google.inject.multibindings.Multibinder;
import org.rakam.config.EncryptionConfig;
import org.rakam.plugin.user.UserActionService;
import org.rakam.util.ConditionalModule;
import org.rakam.plugin.EventProcessor;
import org.rakam.plugin.RakamModule;
import org.rakam.server.http.HttpService;

import static io.airlift.configuration.ConfigBinder.configBinder;

@AutoService(RakamModule.class)
@ConditionalModule(config = "automation.enabled", value = "true")
public class AutomationModule extends RakamModule {
    @Override
    protected void setup(Binder binder) {
        configBinder(binder).bindConfig(EncryptionConfig.class);
        Multibinder<EventProcessor> eventProcessors = Multibinder.newSetBinder(binder, EventProcessor.class);
        eventProcessors.addBinding().to(AutomationEventProcessor.class);
        Multibinder.newSetBinder(binder, UserActionService.class);

        Multibinder<AutomationAction> automationActions = Multibinder.newSetBinder(binder, AutomationAction.class);
        for (AutomationActionType automationActionType : AutomationActionType.values()) {
            automationActions.addBinding().to(automationActionType.getActionClass());
        }

        Multibinder<HttpService> httpServices = Multibinder.newSetBinder(binder, HttpService.class);
        httpServices.addBinding().to(AutomationHttpService.class);
    }

    @Override
    public String name() {
        return "Automation Module";
    }

    @Override
    public String description() {
        return "Take action based on events";
    }
}
