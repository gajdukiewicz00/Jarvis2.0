package org.jarvis.pccontrol.service;

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
import org.jarvis.pccontrol.service.impl.StubDesktopControlService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopControlServiceModeSelectionTest {

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
                    StubDesktopControlService.class);

    @Test
    void usesLinuxDesktopControlServiceByDefaultForLocalRuntime() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DesktopControlService.class);
            assertThat(context.getBean(DesktopControlService.class)).isInstanceOf(LinuxDesktopControlService.class);
        });
    }

    @Test
    void usesStubDesktopControlServiceWhenStubModeIsEnabled() {
        contextRunner.withPropertyValues("pc-control.stub-mode=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(DesktopControlService.class);
                    assertThat(context.getBean(DesktopControlService.class)).isInstanceOf(StubDesktopControlService.class);
                });
    }
}
