-- Buddy Server-only schema (GBTH)
-- Includes only: buddylist, currentuser, loginlog, packet

DROP TABLE IF EXISTS `buddylist`;
CREATE TABLE `buddylist` (
  `Id` varchar(16) NOT NULL,
  `Buddy` varchar(16) NOT NULL,
  `Category` varchar(255) DEFAULT 'General',
  PRIMARY KEY (`Id`,`Buddy`),
  KEY `Buddy` (`Buddy`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `currentuser`;
CREATE TABLE `currentuser` (
  `Id` varchar(16) NOT NULL,
  `Context` int(11) DEFAULT 0,
  `ServerIP` varchar(50) DEFAULT '127.0.0.1',
  `ServerPort` int(11) DEFAULT 8372,
  PRIMARY KEY (`Id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP TABLE IF EXISTS `loginlog`;
CREATE TABLE `loginlog` (
  `LogId` int(11) NOT NULL AUTO_INCREMENT,
  `Id` varchar(16) NOT NULL,
  `Ip` varchar(50) DEFAULT NULL,
  `Ip_v` varchar(50) DEFAULT NULL,
  `Port` int(11) DEFAULT NULL,
  `Port_v` int(11) DEFAULT NULL,
  `Time` datetime DEFAULT current_timestamp(),
  `ServerIp` varchar(50) DEFAULT NULL,
  `ServerPort` int(11) DEFAULT NULL,
  `Country` int(11) DEFAULT NULL,
  PRIMARY KEY (`LogId`),
  KEY `Id` (`Id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP TABLE IF EXISTS `packet`;
CREATE TABLE `packet` (
  `SerialNo` bigint(20) NOT NULL AUTO_INCREMENT,
  `Receiver` varchar(16) NOT NULL,
  `Sender` varchar(16) NOT NULL,
  `Code` int(10) unsigned NOT NULL DEFAULT 0,
  `Body` varbinary(1024) DEFAULT NULL,
  `Time` datetime DEFAULT current_timestamp(),
  PRIMARY KEY (`SerialNo`),
  KEY `Receiver` (`Receiver`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
