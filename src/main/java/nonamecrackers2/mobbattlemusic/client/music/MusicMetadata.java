package nonamecrackers2.mobbattlemusic.client.music;

public record MusicMetadata(String sourceUrl, String songId, String title, String artist, String album,
		String coverUrl, String coverFileName)
{
	public String displayTitle(String fallback)
	{
		return this.title == null || this.title.isBlank() ? fallback : this.title;
	}
	
	public String displayArtist()
	{
		return this.artist == null || this.artist.isBlank() ? "" : this.artist;
	}
}
