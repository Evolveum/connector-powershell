/*
 * Copyright (c) 2015-2020 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.polygon.connector.powershell;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.ConfigurationException;
import org.identityconnectors.framework.spi.AbstractConfiguration;
import org.identityconnectors.framework.spi.ConfigurationProperty;

/**
 * PowerShell connector configuration.
 *
 * @author Radovan Semancik
 *
 */
public class PowerShellConfiguration extends AbstractConfiguration {

    private static final Log LOG = Log.getLog(PowerShellConfiguration.class);

    /**
     * Execution of PowerShell using a single WinRM (WS-MAN) command.
     */
    public static final String SCRIPT_LANGUAGE_POWERSHELL = "powershell";

    /**
     * Execution of single WinRM (WS-MAN) command (using default shell: cmd.exe).
     */
    public static final String SCRIPT_LANGUAGE_CMD = "cmd";

    /**
     * Long-running PowerShell using the PowerHell loop (no special initialization).
     */
    public static final String SCRIPT_LANGUAGE_POWERHELL = "powerhell";

    /**
     * Long-running PowerShell initialized with Exchange snap-ins using the PowerHell loop.
     */
    public static final String SCRIPT_LANGUAGE_EXCHANGE = "exchange";

    /**
     * The mechanism that will be used to execute scripts on resource.
     * The default WinRM mechanism will execute the script by using
     * WinRM client built into the connector. Local strategy means execution
     * on the local machine where the connector is deployed.
     * Possible values: "winrm", "local".
     * Default value: "winrm"
     */
    private String scriptExecutionMechanism = null;

    public static final String SCRIPT_EXECUTION_MECHANISM_WINRM = "winrm";
    public static final String SCRIPT_EXECUTION_MECHANISM_LOCAL = "local";
    // The "winrs" and "invoke-command" mechanisms may come later.

    /**
     * Hostname of the WinRM server. If not set the ordinary host will be used.
     */
    private String winRmHost = null;

    /**
     * Username used for WinRM authentication. If not set the bind DN will be used.
     */
    private String winRmUsername = null;

    /**
     * Domain name used for WinRM authentication.
     */
    private String winRmDomain = null;

    /**
     * Password used for WinRM authentication. If not set the bind password will be used.
     */
    private GuardedString winRmPassword = null;

    /**
     * WinRM authentication scheme.
     * Possible values: "basic", "ntlm", "credssp".
     * Default value: "ntlm"
     */
    private String winRmAuthenticationScheme = null;

    public static final String WINDOWS_AUTHENTICATION_SCHEME_BASIC = "basic";
    public static final String WINDOWS_AUTHENTICATION_SCHEME_NTLM = "ntlm";
    public static final String WINDOWS_AUTHENTICATION_SCHEME_CREDSSP = "credssp";

    /**
     * Port number of the WinRM service.
     */
    private int winRmPort = 5985;

    /**
     * If set to true then the WinRM client will use HTTPS. Otherwise HTTP will be used.
     */
    private boolean winRmUseHttps = false;

    /**
     * Style of argument processing when invoking powesrhell scripts. If set to 'dashed' (default), then
     * the arguments will be appended to the command in the -arg1 val1 -arg2 val2 form. If set to 'variables'
     * then the arguments will be placed in powershell variables before the command is executed.
     */
    private String powershellArgumentStyle = ARGUMENT_STYLE_DASHED;

    public static final String ARGUMENT_STYLE_DASHED = "dashed";
    public static final String ARGUMENT_STYLE_SLASHED = "slashed";
    public static final String ARGUMENT_STYLE_VARIABLES = "variables";


    @ConfigurationProperty(order = 100)
    public String getScriptExecutionMechanism() {
        return scriptExecutionMechanism;
    }

    public void setScriptExecutionMechanism(String scriptExecitionMechanism) {
        this.scriptExecutionMechanism = scriptExecitionMechanism;
    }

    @ConfigurationProperty(order = 101)
    public String getWinRmHost() {
        return winRmHost;
    }

    public void setWinRmHost(String winRmHost) {
        this.winRmHost = winRmHost;
    }

    @ConfigurationProperty(order = 102)
    public String getWinRmUsername() {
        return winRmUsername;
    }

    public void setWinRmUsername(String winRmUsername) {
        this.winRmUsername = winRmUsername;
    }

    @ConfigurationProperty(order = 103)
    public String getWinRmDomain() {
        return winRmDomain;
    }

    public void setWinRmDomain(String winRmDomain) {
        this.winRmDomain = winRmDomain;
    }

    @ConfigurationProperty(order = 104)
    public GuardedString getWinRmPassword() {
        return winRmPassword;
    }

    public void setWinRmPassword(GuardedString winRmPassword) {
        this.winRmPassword = winRmPassword;
    }

    @ConfigurationProperty(order = 105)
    public String getWinRmAuthenticationScheme() {
        return winRmAuthenticationScheme;
    }

    public void setWinRmAuthenticationScheme(String winRmAuthenticationScheme) {
        this.winRmAuthenticationScheme = winRmAuthenticationScheme;
    }

    @ConfigurationProperty(order = 106)
    public int getWinRmPort() {
        return winRmPort;
    }

    public void setWinRmPort(int winRmPort) {
        this.winRmPort = winRmPort;
    }

    @ConfigurationProperty(order = 107)
    public boolean isWinRmUseHttps() {
        return winRmUseHttps;
    }

    public void setWinRmUseHttps(boolean winRmUseHttps) {
        this.winRmUseHttps = winRmUseHttps;
    }

    @ConfigurationProperty(order = 108)
    public String getPowershellArgumentStyle() {
        return powershellArgumentStyle;
    }

    public void setPowershellArgumentStyle(String powershellArgumentStyle) {
        this.powershellArgumentStyle = powershellArgumentStyle;
    }

    @Override
    public void validate() {
        if (WINDOWS_AUTHENTICATION_SCHEME_CREDSSP.equals(winRmAuthenticationScheme) && winRmDomain == null) {
            throw new ConfigurationException("Domain name is required if CredSSP is used");
        }
    }

}
