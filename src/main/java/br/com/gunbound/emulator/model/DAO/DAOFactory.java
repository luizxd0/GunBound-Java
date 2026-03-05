package br.com.gunbound.emulator.model.DAO;

import br.com.gunbound.emulator.model.DAO.impl.ChestJDBC;
import br.com.gunbound.emulator.model.DAO.impl.MenuJDBC;
import br.com.gunbound.emulator.model.DAO.impl.UserJDBC;

public class DAOFactory {

	public static UserDAO CreateUserDao() {
		return new UserJDBC();
	}

	public static ChestDAO CreateChestDao() {
		return new ChestJDBC();
	}

	public static MenuDAO CreateMenuDao() {
		return new MenuJDBC();
	}

}
