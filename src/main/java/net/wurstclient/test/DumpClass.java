/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.test;

import java.lang.reflect.Constructor;

public class DumpClass
{
	public void dump()
	{
		try
		{
			Class<?> clazz = Class.forName(
				"com.cobblemon.mod.common.net.messages.server.SendOutPokemonPacket");
			System.out.println("Constructors of SendOutPokemonPacket:");
			for(Constructor<?> c : clazz.getConstructors())
			{
				System.out.println(c);
			}
			
			Class<?> clazz2 = Class.forName(
				"com.cobblemon.mod.common.net.messages.server.RequestPlayerInteractionsPacket");
			System.out
				.println("Constructors of RequestPlayerInteractionsPacket:");
			for(Constructor<?> c : clazz2.getConstructors())
			{
				System.out.println(c);
			}
			
			// Also let's try to check other packets
			Class<?> clazz3 = Class.forName(
				"com.cobblemon.mod.common.net.messages.server.BattleChallengePacket");
			System.out.println("Constructors of BattleChallengePacket:");
			for(Constructor<?> c : clazz3.getConstructors())
			{
				System.out.println(c);
			}
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
}
