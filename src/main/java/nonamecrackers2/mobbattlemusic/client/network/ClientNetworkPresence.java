package nonamecrackers2.mobbattlemusic.client.network;

import net.minecraft.client.Minecraft;

/**
 * K14-C: client-only network presence queries. MobBattleMusicNetwork (which
 * loads on dedicated servers) must NOT reference Minecraft directly - this
 * class is only touched from client-side send paths.
 */
public final class ClientNetworkPresence
{
	private ClientNetworkPresence() {}

	/**
	 * Does the server we are connected to have the MBM channel? False for
	 * vanilla/no-MBM servers - ALL C2S sends must be guarded by this
	 * (catalog, sync request, clock probes, marker hits, start reports).
	 */
	public static boolean localServerHasChannel()
	{
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || mc.getConnection().getConnection() == null)
			return false;
		return nonamecrackers2.mobbattlemusic.network.MobBattleMusicNetwork
				.channelIsRemotePresent(mc.getConnection().getConnection());
	}
}
