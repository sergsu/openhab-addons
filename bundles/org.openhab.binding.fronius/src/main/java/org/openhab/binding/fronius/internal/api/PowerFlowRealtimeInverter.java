/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.fronius.internal.api;

import com.google.gson.annotations.SerializedName;

/**
 * The {@link PowerFlowRealtimeInverter} is responsible for storing
 * the "inverter" node of the JSON response
 *
 * @author Thomas Rokohl - Initial contribution
 */
public class PowerFlowRealtimeInverter {

    @SerializedName("DT")
    private double dt;
    @SerializedName("P")
    private double p;
    @SerializedName("E_Day")
    private double eDay;
    @SerializedName("E_Year")
    private double eYear;
    @SerializedName("E_Total")
    private double eTotal;

    public double getDt() {
        return dt;
    }

    public void setDt(double dt) {
        this.dt = dt;
    }

    public double getP() {
        return p;
    }

    public void setP(double p) {
        this.p = p;
    }

    public double geteDay() {
        return eDay;
    }

    public void seteDay(double eDay) {
        this.eDay = eDay;
    }

    public double geteYear() {
        return eYear;
    }

    public void seteYear(double eYear) {
        this.eYear = eYear;
    }

    public double geteTotal() {
        return eTotal;
    }

    public void seteTotal(double eTotal) {
        this.eTotal = eTotal;
    }

}
