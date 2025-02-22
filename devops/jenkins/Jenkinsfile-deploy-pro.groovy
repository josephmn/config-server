@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server'
        NAME_IMG_DOCKER = 'config-server-pro'
        CONTAINER_PORT = '8888'
        HOST_PORT = '8888'
        NETWORK = 'azure-net'
        GIT_CREDENTIALS = credentials('PATH_Jenkins')
        GIT_COMMITTER_NAME = 'josephmn'
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
        NEW_VERSION = ''
    }

    stages {
        stage('Compile Repository') {
            steps {
                script {
                    if (params.NOTIFICATION) {
                        notifyJob('JOB Jenkins', params.CORREO)
                    }
                    echo "######################## : ======> EJECUTANDO COMPILE..."
                    // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                    bat 'mvn clean compile'
                }
            }
        }

        stage('Get Version') {
            steps {
                echo "######################## : ======> OBTENER ARCHIVO TXT VERSION..."
                script {
                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()
                    // Remover -SNAPSHOT si existe
                    version = version.replaceAll("-SNAPSHOT", "")
                    NEW_VERSION = version
                }
            }
        }

        stage('Merge RC into Main') {
            when {
                expression { params.MERGE_MAIN }
            }
            steps {
                echo "######################## : ======> CREANDO MERGE A RAMA MASTER..."
                script {
                    echo "=========> Git config..."
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"
                    """

                    echo "=========> Pull a rama master..."
                    bat """
                        git checkout main
                        git pull origin main
                    """

                    if (params.FORCE_MERGE) {
                        echo "=========> Force merge de rama RC a rama main..."
                        bat """
                            git merge -X theirs ${RELEASE_TAG_NAME}
                        """
                        bat """
                            git push origin main
                        """
                    } else {
                        echo "=========> Merge de rama RC a rama main..."
                        bat """
                            git merge ${RELEASE_TAG_NAME}
                        """
                        bat """
                            git push origin main
                        """
                    }
                }
            }
        }

        stage('Generate Next Version') {
            when {
                expression { params.NEXT_VERSION }
            }
            steps {
                echo "######################## : ======> GENERANDO NUEVA VERSION SNAPSHOT..."
                script {
                    echo "=========> Git config..."
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"
                    """

                    echo "=========> Generar siguiente SNAPSHOT..."
                    bat """
                        git checkout develop
                    """

                    // mvn release:prepare -DreleaseVersion=1.1.0 -DdevelopmentVersion=1.1.1-SNAPSHOT -DautoVersionSubmodules=true -B

                    echo "=========> Ejecutando Maven Release Plugin: prepare..."
                    bat """
                        mvn release:prepare -DautoVersionSubmodules=true -B
                    """

                    echo "=========> Ejecutando Maven Release Plugin: perform..."
                    bat """
                        mvn release:perform -B
                    """
                }
            }
        }

        stage('Build Application with Maven') {
            steps {
                echo "######################## : ======> EJECUTANDO BUILD APPLICATION MAVEN..."
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat """
                    git checkout ${RELEASE_TAG_NAME}
                """

                bat 'mvn clean install'
            }
        }

        stage('Creating Network for Docker') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO CREACION DE RED PARA DOCKER: ${NETWORK}..."
                script {
                    def networkExists = bat(
                            script: "docker network ls | findstr ${NETWORK}",
                            returnStatus: true
                    )
                    if (networkExists != 0) {
                        echo "######################## : ======> La red '${NETWORK}' no existe. Creándola..."
                        bat "docker network create --attachable ${NETWORK}"
                    } else {
                        echo "######################## : ======> La red '${NETWORK}' ya existe. No es necesario crearla."
                    }
                }
            }
        }

        stage('Docker Build and Run') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."
                script {
                    echo "=========> VERSION RC: ${NEW_VERSION}"
                    echo "=========> APLICATIVO + VERSION: ${NAME_APP}:${NEW_VERSION}"
                    // Usar la versión capturada para los comandos Docker

                    // Verificar si existe el contenedor
                    def containerExists = bat(
                            script: "@docker ps -a --format '{{.Names}}' | findstr /i \"${NAME_APP}\"",
                            returnStatus: true
                    ) == 0

                    // Verificar si existe la imagen
                    def imageExists = bat(
                            script: "@docker images ${NAME_IMG_DOCKER} --format '{{.Repository}}' | findstr /i \"${NAME_IMG_DOCKER}\"",
                            returnStatus: true
                    ) == 0

                    if (containerExists || imageExists) {
                        echo "=========> Se encontraron recursos existentes, procediendo a limpiarlos..."

                        if (containerExists) {
                            echo "=========> Eliminando contenedor existente: ${NAME_APP}"
                            bat "docker stop ${NAME_APP}"
                            bat "docker rm ${NAME_APP}"
                        }

                        if (imageExists) {
                            echo "=========> Eliminando imagen existente: ${NAME_IMG_DOCKER}"
                            bat "docker rmi ${NAME_IMG_DOCKER}:${NEW_VERSION}"
                        }
                    } else {
                        echo "=========> No se encontraron recursos existentes, procediendo con el despliegue..."
                    }

                    echo "=========> VERSION A DESPLEGAR: ${NEW_VERSION}"
                    echo "=========> APLICATIVO + VERSION: ${NAME_APP}:${NEW_VERSION}"

                    bat """
                        echo "=========> Construyendo nueva imagen con version ${NEW_VERSION}..."
                        docker build --build-arg NAME_APP=${NAME_APP} --build-arg JAR_VERSION=${NEW_VERSION} -t ${NAME_IMG_DOCKER}:${NEW_VERSION} .

                        echo "=========> Desplegando el contenedor: ${NAME_APP}..."
                        docker run -d --name ${NAME_APP} -p ${HOST_PORT}:${CONTAINER_PORT} --network=${NETWORK} --env SERVER_PORT=${HOST_PORT} ${NAME_IMG_DOCKER}:${NEW_VERSION}
                    """
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('SUCCESS', params.CORREO)
                }
            }
        }
        failure {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('FAIL', params.CORREO)
                }
            }
        }
    }
}
