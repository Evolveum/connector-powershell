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

import com.evolveum.polygon.common.GuardedStringAccessor;
import com.evolveum.polygon.common.SchemaUtil;
import com.evolveum.powerhell.*;
import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.transport.https.httpclient.DefaultHostnameVerifier;
import org.apache.http.client.config.AuthSchemes;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.framework.common.exceptions.*;
import org.identityconnectors.framework.common.objects.*;
import org.identityconnectors.framework.spi.Configuration;
import org.identityconnectors.framework.spi.ConnectorClass;
import org.identityconnectors.framework.spi.PoolableConnector;
import org.identityconnectors.framework.spi.operations.ScriptOnResourceOp;
import org.identityconnectors.framework.spi.operations.TestOp;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

@ConnectorClass(displayNameKey = "connector.powershell.display", configurationClass = PowerShellConfiguration.class)
public class PowerShellConnector implements PoolableConnector, TestOp, ScriptOnResourceOp {

    private static final Log LOG = Log.getLog(PowerShellConnector.class);

    private static final String PING_COMMAND = "hostname.exe";
    private static final String EXCHANGE_INIT_SCRIPT = "Add-PSSnapin *Exchange*";

    private PowerShellConfiguration configuration;

    private String winRmUsername;
    private String winRmHost;
    private HostnameVerifier hostnameVerifier;
    private Map<String,PowerHell> powerHellMap = new HashMap<>(); // key: scripting language

    private boolean busInitialized = false;
    private boolean isWinRmInitialized;

    private static int busUsageCount = 0;

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void init(Configuration configuration) {
        LOG.info("Initializing {0} connector instance {1}", this.getClass().getSimpleName(), this);
        this.configuration = (PowerShellConfiguration)configuration;
    }

    @Override
    public void dispose() {
        for (Map.Entry<String,PowerHell> entry: powerHellMap.entrySet()) {
            entry.getValue().disconnect();
        }
        if (busInitialized) {
            disposeBus();
            busInitialized = false;
        }
    }

    @Override
    public void test() {
        LOG.info("Test {0} connector instance {1}", this.getClass().getSimpleName(), this);
        cleanupScriptingBeforeTest();
        checkAlive();
        pingScripting();
    }

    @Override
    public void checkAlive() {
//        TODO;
    }

    @Override
    public Object runScriptOnResource(ScriptContext scriptCtx, OperationOptions options) {
        String scriptLanguage = scriptCtx.getScriptLanguage();
        PowerHell powerHell = getPowerHell(scriptLanguage);

        String command = scriptCtx.getScriptText();
        OperationLog.log("{0} Script REQ {1}: {2}", winRmHost, scriptLanguage, command);
        LOG.ok("Executing {0} script on {0} as {1} using {2}: {3}", scriptLanguage, winRmHost, winRmUsername, powerHell.getImplementationName(), command);

        String output;
        try {

            output = powerHell.runCommand(command, scriptCtx.getScriptArguments());

        } catch (PowerHellException e) {
            OperationLog.error("{0} Script ERR {1}", winRmHost, e.getMessage());
            throw new ConnectorException("Script execution failed: "+e.getMessage(), e);
        }

        OperationLog.log("{0} Script RES {1}", winRmHost, (output==null||output.isEmpty())?"no output":("output "+output.length()+" chars"));
        LOG.ok("Script returned output\n{0}", output);

        return output;
    }

    private PowerHell getPowerHell(String scriptLanguage) {
        if (scriptLanguage == null) {
            throw new IllegalArgumentException("Script language not specified");
        }
        PowerHell powerHell = powerHellMap.get(scriptLanguage);
        if (powerHell == null) {
            powerHell = createPowerHell(scriptLanguage);
            try {
                powerHell.connect();
            } catch (PowerHellExecutionException e) {
                throw new ConnectorException("Cannot connect PowerHell "+powerHell.getImplementationName()+": "+e.getMessage(), e);
            } catch (PowerHellSecurityException e) {
                throw new ConnectorSecurityException("Cannot connect PowerHell "+powerHell.getImplementationName()+": "+e.getMessage(), e);
            } catch (PowerHellCommunicationException e) {
                throw new ConnectorIOException("Cannot connect PowerHell "+powerHell.getImplementationName()+": "+e.getMessage(), e);
            }
            powerHellMap.put(scriptLanguage, powerHell);
        }
        return powerHell;
    }

    private PowerHell createPowerHell(String scriptLanguage) {
        if (!isWinRmInitialized) {
            initWinRm();
        }
        PowerHell powerHell;
        switch (scriptLanguage) {
            case PowerShellConfiguration.SCRIPT_LANGUAGE_CMD:
                powerHell = createCmdPowerHell();
                break;
            case PowerShellConfiguration.SCRIPT_LANGUAGE_POWERSHELL:
                powerHell = createPowershellPowerHell();
                break;
            case PowerShellConfiguration.SCRIPT_LANGUAGE_EXCHANGE:
                powerHell = createLoopPowerHell(EXCHANGE_INIT_SCRIPT);
                break;
            case PowerShellConfiguration.SCRIPT_LANGUAGE_POWERHELL:
                powerHell = createLoopPowerHell(null);
                break;
            default:
                throw new IllegalArgumentException("Unknown script language "+scriptLanguage);
        }
        LOG.ok("Initialized PowerHell {0} ({1}) for language {2}", powerHell.getImplementationName(), powerHell.getClass().getSimpleName(), scriptLanguage);
        return powerHell;
    }

    private PowerHell createCmdPowerHell() {
        if (isScriptingWinRm()) {
            PowerHellWinRmExecImpl powerHell = new PowerHellWinRmExecImpl();
            setWinRmParameters(powerHell);
            return powerHell;
        } else if (isScriptingLocal()) {
            PowerHellLocalExecImpl powerHell = new PowerHellLocalExecImpl();
            setLocalParameters(powerHell);
            return powerHell;
        } else {
            throw new IllegalArgumentException("Unknown scripting execution mechanism "+configuration.getScriptExecutionMechanism());
        }
    }

    private PowerHell createPowershellPowerHell() {
        if (isScriptingWinRm()) {
            PowerHellWinRmExecPowerShellImpl powerHell = new PowerHellWinRmExecPowerShellImpl();
            setWinRmParameters(powerHell);
            return powerHell;
        } else if (isScriptingLocal()) {
            PowerHellLocalExecPowerShellImpl powerHell = new PowerHellLocalExecPowerShellImpl();
            setLocalParameters(powerHell);
            return powerHell;
        } else {
            throw new IllegalArgumentException("Unknown scripting execution mechanism "+configuration.getScriptExecutionMechanism());
        }
    }

    private PowerHell createLoopPowerHell(String initSctip) {
        if (isScriptingWinRm()) {
            PowerHellWinRmLoopImpl powerHell = new PowerHellWinRmLoopImpl();
            setWinRmParameters(powerHell);
            powerHell.setInitScriptlet(initSctip);
            return powerHell;
        } else if (isScriptingLocal()) {
            throw new UnsupportedOperationException("PowerHell loop is not supported for local script execution mechanism");
        } else {
            throw new IllegalArgumentException("Unknown scripting execution mechanism "+configuration.getScriptExecutionMechanism());
        }
    }

    private boolean isScriptingWinRm() {
        return configuration.getScriptExecutionMechanism() == null || PowerShellConfiguration.SCRIPT_EXECUTION_MECHANISM_WINRM.equals(configuration.getScriptExecutionMechanism());
    }

    private boolean isScriptingLocal() {
        return PowerShellConfiguration.SCRIPT_EXECUTION_MECHANISM_LOCAL.equals(configuration.getScriptExecutionMechanism());
    }

    private void setWinRmParameters(AbstractPowerHellWinRmImpl powerHell) {
        setCommonParameters(powerHell);
        String winRmDomain = configuration.getWinRmDomain();
        powerHell.setDomainName(winRmDomain);
        powerHell.setEndpointUrl(getWinRmEndpointUrl());
        powerHell.setUserName(winRmUsername);
        powerHell.setPassword(getWinRmPassword());
        powerHell.setAuthenticationScheme(getAuthenticationScheme());
        powerHell.setHostnameVerifier(hostnameVerifier);
        powerHell.setDisableCertificateChecks(configuration.isDisableCertificateChecks());
    }

    private void setLocalParameters(PowerHellLocalExecImpl powerHell) {
        setCommonParameters(powerHell);
    }

    private void setCommonParameters(AbstractPowerHellImpl powerHell) {
        powerHell.setArgumentStyle(getArgumentStyle());
    }

    private ArgumentStyle getArgumentStyle() {
        if (configuration.getPowershellArgumentStyle() == null) {
            return ArgumentStyle.PARAMETERS_DASH;
        }
        switch (configuration.getPowershellArgumentStyle()) {
            case PowerShellConfiguration.ARGUMENT_STYLE_DASHED:
                return ArgumentStyle.PARAMETERS_DASH;
            case PowerShellConfiguration.ARGUMENT_STYLE_SLASHED:
                return ArgumentStyle.PARAMETERS_SLASH;
            case PowerShellConfiguration.ARGUMENT_STYLE_VARIABLES:
                return ArgumentStyle.VARIABLES;
            default:
                throw new IllegalArgumentException("Unknown argument style "+configuration.getPowershellArgumentStyle());
        }
    }

    private String getAuthenticationScheme() {
        if (configuration.getWinRmAuthenticationScheme() == null) {
            return AuthSchemes.NTLM;
        }
        if (PowerShellConfiguration.WINDOWS_AUTHENTICATION_SCHEME_BASIC.equals(configuration.getWinRmAuthenticationScheme())) {
            return AuthSchemes.BASIC;
        }
        if (PowerShellConfiguration.WINDOWS_AUTHENTICATION_SCHEME_NTLM.equals(configuration.getWinRmAuthenticationScheme())) {
            return AuthSchemes.NTLM;
        }
        if (PowerShellConfiguration.WINDOWS_AUTHENTICATION_SCHEME_CREDSSP.equals(configuration.getWinRmAuthenticationScheme())) {
            return AuthSchemes.CREDSSP;
        }
        throw new ConfigurationException("Unknown authentication scheme: "+configuration.getWinRmAuthenticationScheme());
    }

    private void initWinRm() {
        if (!busInitialized) {
            initBus();
            busInitialized = true;
        }
        winRmUsername = getWinRmUsername();
        winRmHost = getWinRmHost();

        if (configuration.isDisableCertificateChecks()) {
            hostnameVerifier = new AllowAllHostnameVerifier();
        } else {
            hostnameVerifier = new DefaultHostnameVerifier(null);
        }

        isWinRmInitialized = true;
    }


    private boolean isScriptingExplicitlyConfigured() {
        if (configuration.getScriptExecutionMechanism() != null) {
            return true;
        }
        if (configuration.getWinRmUsername() != null) {
            return true;
        }
        if (configuration.getWinRmPassword() != null) {
            return true;
        }
        if (configuration.getWinRmDomain() != null) {
            return true;
        }
        if (configuration.getWinRmAuthenticationScheme() != null) {
            return true;
        }
        return false;
    }



    private void pingScripting() {
        String command = PING_COMMAND;
        PowerHell powerHell = getPowerHell(PowerShellConfiguration.SCRIPT_LANGUAGE_CMD);

        OperationLog.log("{0} Script REQ ping cmd: {1}", winRmHost, command);
        LOG.ok("Executing ping cmd script on {0} as {1}: {2}", winRmHost, winRmUsername, command);

        try {

            String output = powerHell.runCommand(PING_COMMAND, null);

            OperationLog.log("{0} Script RES ping: {1}", winRmHost, output);

        } catch (PowerHellExecutionException e) {
            OperationLog.error("{0} Script ERR ping status={1}: {2}", winRmHost, e.getExitCode(), e.getMessage());
            LOG.error("Script ping error, exit status = {0}\nOUT:\n{1}\nERR:\n{2}", e.getExitCode(), e.getStdout(), e.getStderr());
            throw new ConnectorException("Ping script execution failed (status code "+e.getExitCode()+"): "+e.getMessage(), e);
        } catch (PowerHellSecurityException | PowerHellCommunicationException e) {
            OperationLog.error("{0} Script ERR ping: {2}", winRmHost, e.getMessage());
            throw new ConnectorException("Ping script execution failed: "+e.getMessage(), e);
        }
    }

    private void cleanupScriptingBeforeTest() {
        for (Map.Entry<String,PowerHell> entry: powerHellMap.entrySet()) {
            entry.getValue().disconnect();
        }
        powerHellMap.clear();
        winRmUsername = null;
        winRmHost = null;
        hostnameVerifier = null;
        isWinRmInitialized = false;
    }


    /*
     * Init and dispose methods for the CXF bus. These are based on static usage
     * counter and static default bus. Which means that the bus will be reused by
     * all the connector instances (even those that have different configuration).
     * But as  WinRmTool tool creates new WinRmClient for each invocation which
     * in turn creates a new CXF service then this approach should be safe.
     * This is the best that we can do as ConnId does not provide any
     * connector context that we could use to store per-resource bus instance.
     */
    private static synchronized void initBus() {
        busUsageCount++;
        LOG.ok("bus init (usage count = {0})", busUsageCount);
        // make sure that the bus is created here while we are synchronized
        BusFactory.getDefaultBus(true);
    }

    private static synchronized void disposeBus() {
        busUsageCount--;
        LOG.ok("bus dispose (usage count = {0})", busUsageCount);
        if (busUsageCount == 0) {
            Bus bus = BusFactory.getDefaultBus(false);
            if (bus != null) {
                LOG.ok("Shutting down WinRm CXF bus {0}", bus);
                bus.shutdown(true);
                LOG.ok("Bus shut down");
            }
        }
    }

    private String getWinRmHost() {
        return configuration.getWinRmHost();
    }

    private String getWinRmUsername() {
        return configuration.getWinRmUsername();
    }

    private String getWinRmPassword() {
        GuardedString winRmPassword = configuration.getWinRmPassword();
        if (winRmPassword == null) {
            return null;
        }
        GuardedStringAccessor accessor = new GuardedStringAccessor();
        winRmPassword.access(accessor);
        return new String(accessor.getClearChars());
    }

    private String getWinRmEndpointUrl() {
        StringBuilder sb = new StringBuilder();
        if (configuration.isWinRmUseHttps()) {
            sb.append("https://");
        } else {
            sb.append("http://");
        }
        sb.append(winRmHost).append(":").append(configuration.getWinRmPort());
        sb.append("/wsman");
        return sb.toString();
    }

    private class AllowAllHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    }
}
