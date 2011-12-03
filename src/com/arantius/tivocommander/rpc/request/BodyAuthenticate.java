/*
Open Commander for TiVo allows control of a TiVo Premiere device.
Copyright (C) 2011  Anthony Lieuallen (arantius@gmail.com)

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program; if not, write to the Free Software Foundation, Inc.,
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/

package com.arantius.tivocommander.rpc.request;

import java.util.HashMap;
import java.util.Map;

public class BodyAuthenticate extends MindRpcRequest {
  public BodyAuthenticate(String mak) {
    super("bodyAuthenticate");

    Map<String, String> credential = new HashMap<String, String>();
    credential.put("type", "makCredential");
    credential.put("key", mak);
    mDataMap.put("credential", credential);
  }
}
