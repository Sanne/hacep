/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.redhat.hacep.playground.rules.model;

import it.redhat.hacep.model.Key;
import it.redhat.hacep.playground.cache.UserEventKey;

import java.time.Instant;


public class ChangePasswordEvent extends UserEvent {

    private String newPwd;
    private String oldPwd;

    public ChangePasswordEvent(long id, Instant instant, String usr, String oldPwd, String newPwd) {
        super(id, usr, instant);
        this.oldPwd = oldPwd;
        this.newPwd = newPwd;
    }

    public String getOldPwd() {
        return oldPwd;
    }

    public String getNewPwd() {
        return newPwd;
    }

    @Override
    public Instant getInstant() {
        return null;
    }

    @Override
    public Key extractKey() {
        return new UserEventKey(String.valueOf(this.getId()), String.valueOf(this.getUsr()));
    }

}
