package com.osrsbingo.api;

import lombok.Value;

@Value
public class BoardStatus
{
	String eventStatus;
	String revision;
	int pendingCount;
}
