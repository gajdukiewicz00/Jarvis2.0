package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.service.impl.LinuxSystemControlService;
import org.jarvis.pccontrol.config.DesktopControlProperties;
import org.jarvis.pccontrol.service.impl.DesktopEntryApplicationCatalog;
import org.jarvis.pccontrol.service.impl.LinuxAudioControl;
import org.jarvis.pccontrol.service.impl.LinuxBrowserControl;
import org.jarvis.pccontrol.service.impl.LinuxDesktopControlService;
import org.jarvis.pccontrol.service.impl.LinuxInputControl;
import org.jarvis.pccontrol.service.impl.LinuxSystemInfoProvider;
import org.jarvis.pccontrol.service.impl.LinuxWindowControl;
import org.jarvis.pccontrol.service.impl.PathCommandLocator;
import org.jarvis.pccontrol.service.impl.ProcessCommandExecutor;
import org.jarvis.pccontrol.service.impl.StubSystemControlService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SystemControlServiceModeSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    DesktopControlProperties.class,
                    ProcessCommandExecutor.class,
                    PathCommandLocator.class,
                    DesktopEntryApplicationCatalog.class,
                    LinuxBrowserControl.class,
                    LinuxAudioControl.class,
                    LinuxSystemInfoProvider.class,
                    LinuxWindowControl.class,
                    LinuxInputControl.class,
                    LinuxDesktopControlService.class,
                    LinuxSystemControlService.class,
                    StubSystemControlService.class);

    @Test
    void usesLinuxSystemControlServiceByDefaultForLocalRuntime() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SystemControlService.class);
            assertThat(context.getBean(SystemControlService.class)).isInstanceOf(LinuxSystemControlService.class);
        });
    }

    @Test
    void usesStubSystemControlServiceWhenStubModeIsEnabled() {
        contextRunner.withPropertyValues("pc-control.stub-mode=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(SystemControlService.class);
                    assertThat(context.getBean(SystemControlService.class)).isInstanceOf(StubSystemControlService.class);
                });
    }
}
